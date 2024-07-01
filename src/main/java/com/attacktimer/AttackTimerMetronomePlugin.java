package com.attacktimer;


/*
 * Copyright (c) 2022, Nick Graves <https://github.com/ngraves95>
 * Copyright (c) 2024, Lexer747 <https://github.com/Lexer747>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Dimension;
import javax.inject.Inject;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Deque;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.SoundEffectPlayed;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemStats;

@PluginDescriptor(
        name = "Attack Timer Metronome",
        description = "Shows a visual cue on an overlay every game tick to help timing based activities",
        tags = {"timers", "overlays", "tick", "skilling"}
)
public class AttackTimerMetronomePlugin extends Plugin
{
    public enum AttackState {
        NOT_ATTACKING,
        DELAYED_FIRST_TICK,
        DELAYED,
    }

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private AttackTimerMetronomeTileOverlay overlay;

    @Inject
    private AttackTimerBarOverlay barOverlay;

    @Inject
    private AttackTimerMetronomeConfig config;

    @Inject
    private ItemManager itemManager;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    public int tickPeriod = 0;

    final int ATTACK_DELAY_NONE = 0;

    private int uiUnshowDebounceTickCount = 0;
    private int uiUnshowDebounceTicksMax = 1;

    public int attackDelayHoldoffTicks = ATTACK_DELAY_NONE;

    public AttackState attackState = AttackState.NOT_ATTACKING;
    // The state of the renderer, will lag a few cycles behind the plugin's state
    public AttackState renderedState = AttackState.NOT_ATTACKING;

    public Color CurrentColor = Color.WHITE;

    public int DEFAULT_SIZE_UNIT_PX = 25;

    public static final int SALAMANDER_SET_ANIM_ID = 952; // Used by all 4 types of salamander https://oldschool.runescape.wiki/w/Salamander

    private final int DEFAULT_FOOD_ATTACK_DELAY_TICKS = 3;
    private final int KARAMBWAN_ATTACK_DELAY_TICKS = 2;
    public static final int EQUIPPING_MONOTONIC = 384; // From empirical testing this clientint seems to always increase whenever the player equips an item
    private Spellbook currentSpellBook = Spellbook.STANDARD;
    private int lastEquippingMonotonicValue = -1;
    private int soundEffectTick = -1;
    private int soundEffectId = -1;
    public Dimension DEFAULT_SIZE = new Dimension(DEFAULT_SIZE_UNIT_PX, DEFAULT_SIZE_UNIT_PX);


    // region subscribers

    @Subscribe
    public void onVarbitChanged(VarbitChanged varbitChanged)
    {
        if (varbitChanged.getVarbitId() == Varbits.SPELLBOOK)
        {
            currentSpellBook = Spellbook.fromVarbit(varbitChanged.getValue());
        }
    }

    // onVarbitChanged happens when the user causes some interaction therefore we can't rely on some fixed
    // timing relative to a tick. A player can swap many items in the duration of the a tick.
    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged varClientIntChanged)
    {
        final int currentMagicVarBit = client.getVarcIntValue(EQUIPPING_MONOTONIC);
        if (currentMagicVarBit <= lastEquippingMonotonicValue)
        {
            return;
        }
        lastEquippingMonotonicValue = currentMagicVarBit;

        // This windowing safe guards of from late swaps inside a tick, if we have already rendered the tick
        // then we shouldn't perform another attack.
        boolean preAttackWindow = attackState == AttackState.DELAYED_FIRST_TICK && renderedState != attackState;
        if (preAttackWindow)
        {
            // "Perform an attack" this is overwrites the last attack since we now know the user swapped
            // "Something" this tick, the equipped weapon detection will pick up specific weapon swaps. Even
            // swapping more than 1 weapon inside a single tick.
            performAttack();
        }
    }

    // onSoundEffectPlayed used to track spell casts, for when the player casts a spell on first tick coming
    // off cooldown, in some cases (e.g. ice barrage) the player will have no animation. Also they don't have
    // a projectile to detect instead :/
    @Subscribe
    public void onSoundEffectPlayed(SoundEffectPlayed event)
    {
        // event.getSource() will be null if the player cast a spell, it's only for area sounds.
        soundEffectTick = client.getTickCount();
        soundEffectId = event.getSoundId();
    }

    // endregion

    @Provides
    AttackTimerMetronomeConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AttackTimerMetronomeConfig.class);
    }

    private int getItemIdFromContainer(ItemContainer container, int slotID)
    {
        if (container == null) {
            return -1;
        }
        final Item item = container.getItem(slotID);
        return (item != null) ? item.getId() : -1;
    }

    private int getWeaponId() {
        return getItemIdFromContainer(client.getItemContainer(InventoryID.EQUIPMENT),
                EquipmentInventorySlot.WEAPON.getSlotIdx());
    }

    private ItemStats getWeaponStats(int weaponId)
    {
        return itemManager.getItemStats(weaponId, false);
    }

    private AttackStyle getAttackStyle()
    {
        final int currentAttackStyleVarbit = client.getVarpValue(VarPlayer.ATTACK_STYLE);
        final int currentEquippedWeaponTypeVarbit = client.getVarbitValue(Varbits.EQUIPPED_WEAPON_TYPE);
        AttackStyle[] attackStyles = WeaponType.getWeaponType(currentEquippedWeaponTypeVarbit).getAttackStyles();

        if (currentAttackStyleVarbit < attackStyles.length) {
            return attackStyles[currentAttackStyleVarbit];
        }

        return AttackStyle.ACCURATE;
    }

    private int applyRangedAndMeleeRelicSpeed(int baseSpeed)
    {
        if (baseSpeed >= 4) {
            return baseSpeed / 2;
        } else {
            return (baseSpeed + 1) / 2;
        }
    }

    static final int BloodMoonSetAnimId = 2792;

    private boolean getBloodMoonProc()
    {
        return client.getLocalPlayer().hasSpotAnim(BloodMoonSetAnimId);
    }

    private boolean getSalamanderAttack()
    {
        return client.getLocalPlayer().hasSpotAnim(SALAMANDER_SET_ANIM_ID);
    }

    private int adjustSpeedForLeaguesIfApplicable(int baseSpeed)
    {
        int leagueRelicVarbit = 0;
        if (client.getWorldType().contains(WorldType.SEASONAL)) {
            leagueRelicVarbit = client.getVarbitValue(Varbits.LEAGUE_RELIC_4);
        }

        AttackStyle attackStyle = getAttackStyle();

        switch (leagueRelicVarbit) {
            case 0:
                // No league relic active - player does not have t4 relic or is not in leagues.
                return baseSpeed;
            case 1:
                // Archer's Embrace (ranged).
                if (attackStyle == AttackStyle.RANGING ||
                        attackStyle == AttackStyle.LONGRANGE) {
                    return applyRangedAndMeleeRelicSpeed(baseSpeed);
                }
                break;
            case 2:
                // Brawler's Resolve (melee)
                if (attackStyle == AttackStyle.ACCURATE ||
                        attackStyle == AttackStyle.AGGRESSIVE ||
                        attackStyle == AttackStyle.CONTROLLED ||
                        attackStyle == AttackStyle.DEFENSIVE) {
                    return applyRangedAndMeleeRelicSpeed(baseSpeed);
                }
                break;
            case 3:
                // Superior Sorcerer (magic)
                if (attackStyle == AttackStyle.CASTING ||
                        attackStyle == AttackStyle.DEFENSIVE_CASTING) {
                    return 2;
                }
                break;
        }

        return baseSpeed;
    }

    private void setAttackDelay()
    {
        // If the weapon Id isn't consistent across this method we race with the player if they're switching
        // mid tick, so we have to cache this here.
        int weaponId = getWeaponId();
        AnimationData curAnimation = AnimationData.fromId(client.getLocalPlayer().getAnimation());
        PoweredStaves stave = PoweredStaves.getPoweredStaves(weaponId, curAnimation);
        boolean matchesSpellbook = matchesSpellbook(curAnimation);
        // Slow path - first blindly guess the speed without checking projectile
        attackDelayHoldoffTicks = getWeaponSpeed(weaponId, null, stave, curAnimation, matchesSpellbook);
        if (stave == null)
        {
            // Fast path, no need to check the stave code
            return;
        }

        // Now do more thorough checks (most weapons don't need this stuff and exit early, melee and ranged) -
        // However if it's a powered staff and a casting animation tied to one of the staves. Then we don't
        // actually know if the staff is casting a builtin spell or something off the current spellbook. We
        // can do slightly better by checking the projectile we fired, as the powered staves have custom
        // projectiles. But we start in the above getWeaponSpeed call to just assume holding the staff means
        // 4-tick.
        Integer firedProjectile = getPlayerFiredProjectile();
        int startingTick = client.getTickCount();
        if (firedProjectile != null)
        {
            // We got the projectile so simply call it again with the update.
            attackDelayHoldoffTicks = getWeaponSpeed(weaponId, firedProjectile, stave, curAnimation, true);
        }
        else
        {
            // However in this case the projectile isn't known yet, it will appear the tick later, we can
            // retroactively update (add a tick of delay) should the projectile never appear (ancients) or
            // doesn't match.
            clientThread.invokeLater(() ->
            {
                int currentTick = client.getTickCount();
                if (client.getTickCount() > startingTick+2)
                {
                    return true; // give up
                }
                int currentWeaponId = getWeaponId();
                PoweredStaves currentStave = PoweredStaves.getPoweredStaves(currentWeaponId, curAnimation);
                Integer newProjectile = getPlayerFiredProjectile();
                if (newProjectile == null)
                {
                    return false; // keep trying
                }
                int delta = currentTick - startingTick;
                if (delta == 0 || delta == 1)
                {
                    if (currentStave.MatchesProjectile(newProjectile))
                    {
                        return true; // found! and nothing to do we already set the timer to 4
                    }
                }
                // Our delta is too large or we found a projectile which didn't match, this most likely means
                // a cast so add the tick of delay
                attackDelayHoldoffTicks += 1;
                return true;
            });
        }
    }

    // matchesSpellbook tries two methods, matching the animation the spell book based on the enum of
    // pre-coded matches, and then the second set of matches against the known sound id of the spell (which
    // unfortunately doesn't work if the player has them disabled).
    private boolean matchesSpellbook(AnimationData curAnimation)
    {
        if (curAnimation != null && curAnimation.matchesSpellbook(currentSpellBook))
        {
            return true;
        }
        if (client.getTickCount() == soundEffectTick)
        {
            return CastingSoundData.getSpellBookFromId(soundEffectId) == currentSpellBook;
        }
        return false;
    }

    private int getWeaponSpeed(int weaponId, Integer firedProjectile, PoweredStaves maybeStave, AnimationData curAnimation, boolean matchesSpellbook)
    {
        if (maybeStave != null)
        {
            // We are currently dealing with a staves in which case we can make decisions based on the
            // projectile and if the stave matches the projectile. If the projectile is null then we just
            // assume the stave is being used since it's equipped, we will be updated later with the
            // projectile should it appear.
            //
            // We also just use the stave 4 tick speed if we are definitely NOT casting manually, i.e. the
            // animation/sound doesn't match the current spell book because that animation can only come from
            // the inbuilt spell and not a manual cast.
            if (!matchesSpellbook || firedProjectile == null || maybeStave.MatchesProjectile(firedProjectile))
            {
                return adjustSpeedForLeaguesIfApplicable(4);
            }
        }
        if (matchesSpellbook && isManualCasting(curAnimation))
        { // You can cast with anything equipped in which case we shouldn't look to invent for speed, it will instead always be 5.
            return adjustSpeedForLeaguesIfApplicable(5);
        }

        ItemStats weaponStats = getWeaponStats(weaponId);
        if (weaponStats == null)
        {
            return adjustSpeedForLeaguesIfApplicable(4); // Assume barehanded == 4t
        }
        ItemEquipmentStats e = weaponStats.getEquipment();
        int speed = e.getAspeed();

        if (getAttackStyle() == AttackStyle.RANGING && client.getVarpValue(VarPlayer.ATTACK_STYLE) == 1)
        { // Hack for index 1 => rapid
            speed -= 1; // Assume ranging == rapid. Also works for salamanders which attack 1 tick faster when using the ranged style
        }
        if (getBloodMoonProc())
        { // Similar hack for the blood moon set, it's essentially rapid
            speed -= 1;
        }

        return adjustSpeedForLeaguesIfApplicable(speed); // Deadline for next available attack.
    }

    private boolean isPlayerAttacking()
    {
        int animationId = client.getLocalPlayer().getAnimation();
        Actor target = client.getLocalPlayer().getInteracting();
        if (target != null && (target instanceof NPC))
        {
            boolean isTargetDummy = target.getHealthRatio() == -1 && target.getCombatLevel() == 0;
            boolean attackingNPC = target.getCombatLevel() > 0 || isTargetDummy;
            // Not walking is either any player animation or the edge cases which don't trigger an animation, e.g Salamander.
            boolean notWalking = animationId != -1 || getSalamanderAttack();
            // just having a target is not enough the player may be out of
            // range, we must wait for any animation which isn't
            // running/walking/etc
            return attackingNPC && notWalking;
        }
        AnimationData fromId = AnimationData.fromId(animationId);
        if (fromId == AnimationData.RANGED_BLOWPIPE || fromId == AnimationData.RANGED_BLAZING_BLOWPIPE)
        {
            // These two animations are the only ones which exceed the duration of their attack cooldown (when
            // on rapid), so in this case DO NOT fall back the animation as it is un-reliable.
            return false;
        }
        // fall back to animations.
        return fromId != null;
    }

    private boolean isManualCasting(AnimationData curId)
    {
        // If you use a weapon like a blow pipe which has an animation longer than it's cool down then cast an
        // ancient attack it wont have an animation at all. We can therefore need to detect this with a list
        // of sounds instead. This obviously doesn't work if the player is muted but 🤷‍♂️ but ATM I can't
        // think of a way to detect this type of attack as a cast, only sound is an indication that the player
        // is on cooldown, melee attacks, etc will trigger an animation overwriting the last frame of the
        // blowpipe's idle animation.
        boolean castingFromSound = client.getTickCount() == soundEffectTick ? CastingSoundData.isCastingSound(soundEffectId) : false;
        boolean castingFromAnimation = AnimationData.isManualCasting(curId);
        return castingFromSound || castingFromAnimation;
    }

    private void performAttack()
    {
        attackState = AttackState.DELAYED_FIRST_TICK;
        setAttackDelay();
        tickPeriod = attackDelayHoldoffTicks;
        uiUnshowDebounceTickCount = uiUnshowDebounceTicksMax;
    }

    private Integer getPlayerFiredProjectile()
    {
        Actor target = client.getLocalPlayer().getInteracting();
        if (target == null)
        {
            return null;
        }
        Deque<Projectile> projectiles = client.getProjectiles();
        for (Projectile projectile : projectiles)
        {
            if (projectile.getInteracting() == target)
            {
                return projectile.getId();
            }
        }
        return null;
    }

    public int getTicksUntilNextAttack()
    {
        return 1 + attackDelayHoldoffTicks;
    }

    public int getWeaponPeriod()
    {
        return tickPeriod;
    }

    public boolean isAttackCooldownPending()
    {
        return attackState == AttackState.DELAYED
            || attackState == AttackState.DELAYED_FIRST_TICK
            || uiUnshowDebounceTickCount > 0;
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (event.getType() != ChatMessageType.SPAM)
        {
            return;
        }

        final String message = event.getMessage();

        if (message.startsWith("You eat") ||
                message.startsWith("You drink the wine")) {
            int attackDelay = (message.toLowerCase().contains("karambwan")) ?
                    KARAMBWAN_ATTACK_DELAY_TICKS :
                    DEFAULT_FOOD_ATTACK_DELAY_TICKS;

            if (attackState == AttackState.DELAYED) {
                attackDelayHoldoffTicks += attackDelay;
            }
        }
    }

    // onInteractingChanged is the driver for detecting if the player attacked out side the usual tick window
    // of the onGameTick events.
    @Subscribe
    public void onInteractingChanged(InteractingChanged interactingChanged)
    {
        Actor source = interactingChanged.getSource();
        Actor target = interactingChanged.getTarget();

        Player p = client.getLocalPlayer();

        if (source.equals(p) && (target instanceof NPC))
        {

            switch (attackState)
            {
                case NOT_ATTACKING:
                    // If not previously attacking, this action can result in a queued attack or
                    // an instant attack. If its queued, don't trigger the cooldown yet.
                    if (isPlayerAttacking())
                    {
                        performAttack();
                    }
                    break;
                case DELAYED_FIRST_TICK:
                    // fallthrough
                case DELAYED:
                    // Don't reset tick counter or tick period.
                    break;
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        boolean isAttacking = isPlayerAttacking();
        switch (attackState)
        {
            case NOT_ATTACKING:
                if (isAttacking)
                {
                    performAttack(); // Sets state to DELAYED_FIRST_TICK.
                }
                else
                {
                    uiUnshowDebounceTickCount--;
                }
                break;
            case DELAYED_FIRST_TICK:
                // we stay in this state for one tick to allow for 0-ticking
                attackState = AttackState.DELAYED;
                // fallthrough
            case DELAYED:
                if (attackDelayHoldoffTicks <= 0)
                { // Eligible for a new attack
                    if (isAttacking)
                    {
                        performAttack();
                    }
                    else
                    {
                        attackState = AttackState.NOT_ATTACKING;
                    }
                }
        }

        attackDelayHoldoffTicks--;
    }


    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals("attacktimermetronome"))
        {
            attackDelayHoldoffTicks = 0;
        }
    }

    @Override
    protected void startUp() throws Exception
    {
        overlayManager.add(overlay);
        overlay.setPreferredSize(DEFAULT_SIZE);
        overlayManager.add(barOverlay);
    }

    @Override
    protected void shutDown() throws Exception
    {
        overlayManager.remove(overlay);
        overlayManager.remove(barOverlay);
        attackDelayHoldoffTicks = 0;
    }
}
