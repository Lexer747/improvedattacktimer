package com.improvedattacktimer;

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

import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Hitsplat;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemStats;

@Slf4j
@PluginDescriptor(name = "Improved Attack Timer", description = "Shows a timer of the players cooldown until the next attack",
	 tags = {"pvm", "timer", "attack", "combat", "weapon" })
public class ImprovedAttackTimerPlugin extends Plugin
{
	public enum AttackState
	{
		NOT_ATTACKING,
		DELAYED,
	}

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private AttackTimerOverlay overlay;

	@Inject
	private BarOverlay barOverlay;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Client client;

	public int tickPeriod = 0;

	final int ATTACK_DELAY_NONE = 0;

	private int uiUnshowDebounceTickCount = 0;
	private int uiUnshowDebounceTicksMax = 1;

	public int attackDelayHoldoffTicks = ATTACK_DELAY_NONE;

	public AttackState attackState = AttackState.NOT_ATTACKING;

	public Color CurrentColor = Color.WHITE;

	public int DEFAULT_SIZE_UNIT_PX = 25;

	private final int DEFAULT_FOOD_ATTACK_DELAY_TICKS = 3;
	private final int KARAMBWAN_ATTACK_DELAY_TICKS = 2;
	public Dimension DEFAULT_SIZE = new Dimension(DEFAULT_SIZE_UNIT_PX, DEFAULT_SIZE_UNIT_PX);

	private Actor RedKerisTarget = null;
	private int RedKerisHitsplatTick = -1;
	private final List<Hitsplat> RedKerisHitsplats = new ArrayList<>();

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied hitsplatApplied)
	{
		Actor target = hitsplatApplied.getActor();
		Hitsplat hitsplat = hitsplatApplied.getHitsplat();
		// Ignore all hitsplats other than mine
		if (!hitsplat.isMine() || target == client.getLocalPlayer())
		{
			return;
		}

		// Currently only the red keris affects attack delay
		if (RedKerisTarget == null || target != RedKerisTarget)
		{
			return;
		}

		if (RedKerisHitsplatTick == client.getTickCount())
		{
			RedKerisHitsplats.add(hitsplat);
		}
	}

	@Provides
	ImprovedAttackerTimerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ImprovedAttackerTimerConfig.class);
	}

	private int getItemIdFromContainer(ItemContainer container, int slotID)
	{
		if (container == null)
		{
			return -1;
		}
		final Item item = container.getItem(slotID);
		return (item != null) ? item.getId() : -1;
	}

	private ItemStats getItemStatsFromContainer(ItemContainer container, int slotID)
	{
		if (container == null)
		{
			return null;
		}
		final Item item = container.getItem(slotID);
		return item != null ? itemManager.getItemStats(item.getId(), false) : null;
	}

	private boolean getRedKerisSpec()
	{
		int animationId = client.getLocalPlayer().getAnimation();
		return AnimationData.fromId(animationId) == AnimationData.MELEE_RED_KERIS_SPEC;
	}

	private ItemStats getWeaponStats()
	{
		return getItemStatsFromContainer(client.getItemContainer(InventoryID.EQUIPMENT),
				EquipmentInventorySlot.WEAPON.getSlotIdx());
	}

	private int getWeaponId()
	{
		// TODO account for 0-ticking - maybe search the invent based on the 384 varbit changes?
		return getItemIdFromContainer(client.getItemContainer(InventoryID.EQUIPMENT),
				EquipmentInventorySlot.WEAPON.getSlotIdx());
	}

	private AttackStyle getAttackStyle()
	{
		final int currentAttackStyleVarbit = client.getVarpValue(VarPlayer.ATTACK_STYLE);
		final int currentEquippedWeaponTypeVarbit = client.getVarbitValue(Varbits.EQUIPPED_WEAPON_TYPE);
		WeaponType weaponType = WeaponType.getWeaponType(currentEquippedWeaponTypeVarbit);
		if (weaponType == null)
		{
			log.debug("unknown attackstyle currentEquippedWeaponTypeVarbit={} currentAttackStyleVarbit={} falling back to ACCURATE",
					currentEquippedWeaponTypeVarbit, currentAttackStyleVarbit);
			return AttackStyle.ACCURATE;
		}
		AttackStyle[] attackStyles = weaponType.getAttackStyles();

		if (currentAttackStyleVarbit < attackStyles.length)
		{
			return attackStyles[currentAttackStyleVarbit];
		}

		return AttackStyle.ACCURATE;
	}

	private int applyRangedAndMeleeRelicSpeed(int baseSpeed)
	{
		if (baseSpeed >= 4)
		{
			return baseSpeed / 2;
		} else
		{
			return (baseSpeed + 1) / 2;
		}
	}

	private int adjustSpeedForLeaguesIfApplicable(int baseSpeed)
	{
		int leagueRelicVarbit = 0;
		if (client.getWorldType().contains(WorldType.SEASONAL))
		{
			leagueRelicVarbit = client.getVarbitValue(Varbits.LEAGUE_RELIC_4);
		}

		AttackStyle attackStyle = getAttackStyle();

		switch (leagueRelicVarbit)
		{
			case 0:
				// No league relic active - player does not have t4 relic or is not in leagues.
				return baseSpeed;
			case 1:
				// Archer's Embrace (ranged).
				if (attackStyle == AttackStyle.RANGING ||
						attackStyle == AttackStyle.LONGRANGE)
						{
					return applyRangedAndMeleeRelicSpeed(baseSpeed);
				}
				break;
			case 2:
				// Brawler's Resolve (melee)
				if (attackStyle == AttackStyle.ACCURATE ||
						attackStyle == AttackStyle.AGGRESSIVE ||
						attackStyle == AttackStyle.CONTROLLED ||
						attackStyle == AttackStyle.DEFENSIVE)
						{
					return applyRangedAndMeleeRelicSpeed(baseSpeed);
				}
				break;
			case 3:
				// Superior Sorcerer (magic)
				if (attackStyle == AttackStyle.CASTING ||
						attackStyle == AttackStyle.DEFENSIVE_CASTING)
						{
					return 2;
				}
				break;
		}

		return baseSpeed;
	}

	static final int BloodMoonSetAnimId = 2792;
	static final int FrostMoonSetAnimId = 2793;

	private boolean getBloodMoonProc()
	{
		return client.getLocalPlayer().hasSpotAnim(BloodMoonSetAnimId);
	}

	private int getWeaponSpeed()
	{
		boolean isPlayerCasting = isPlayerCasting();
		int itemId = getWeaponId();
		boolean isFourTick = WeaponIds.FourTickStaves().contains(itemId);
		if (isPlayerCasting && !isFourTick)
		{
			// Unfortunately, the trident and toxic trident share animations
			// with wave-tier spells. (So do many other powered staves).
			//
			// Assume that if the user is not wielding a four tick stave and
			// isCasting, they are autocasting or manual casting from a
			// spellbook.
			//
			// This assumption breaks if the user is manually casting a spell
			// while wielding a trident or toxic trident (unlikely).
			return adjustSpeedForLeaguesIfApplicable(5);
		}

		ItemStats weaponStats = getWeaponStats();
		if (weaponStats == null)
		{
			return adjustSpeedForLeaguesIfApplicable(4); // Assume barehanded == 4t
		}
		ItemEquipmentStats e = weaponStats.getEquipment();
		int speed = e.getAspeed();

		if (getAttackStyle() == AttackStyle.RANGING && client.getVarpValue(VarPlayer.ATTACK_STYLE) == 1)
		{ // Hack for index 1 => rapid
			speed -= 1; // Assume ranging == rapid.
		}
		if (getBloodMoonProc())
		{ // Similar hack for the blood moon set, it's essentially rapid
			speed -= 1;
		}

		if (getRedKerisSpec())
		{
			speed += 4; // If the spec missed we compensate on the next tick
		}

		return adjustSpeedForLeaguesIfApplicable(speed); // Deadline for next available attack.
	}

	private boolean isPlayerAttackingThisTick()
	{
		int animationId = client.getLocalPlayer().getAnimation();
		if (overlay.config.targeting())
		{
			Actor target = client.getLocalPlayer().getInteracting();
			if (target != null)
			{
				boolean isTargetDummy = target.getHealthRatio() == -1 && target.getCombatLevel() == 0;
				boolean attackingNPC = target.getCombatLevel() > 0 || isTargetDummy;
				boolean notWalking = animationId != -1;
				// just having a target is not enough the player may be out of
				// range, we must wait for any animation which isn't
				// running/walking/etc
				boolean attack = attackingNPC && notWalking;
				if (attack && getRedKerisSpec())
				{
					RedKerisTarget = target;
					RedKerisHitsplatTick = client.getTickCount() + 1;
				}
				return attack;
			}
			return false;
		}
		// Config targeting disabled use the old method of looking for player animations from the hard coded list.
		return AnimationData.fromId(animationId) != null;
	}

	private boolean isPlayerCasting()
	{
		return AnimationData.isCasting(AnimationData.fromId(client.getLocalPlayer().getAnimation()));
	}

	private void performAttack()
	{
		attackState = AttackState.DELAYED;
		attackDelayHoldoffTicks = getWeaponSpeed();
		tickPeriod = attackDelayHoldoffTicks;
		uiUnshowDebounceTickCount = uiUnshowDebounceTicksMax;
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
		return (attackState == AttackState.DELAYED) || uiUnshowDebounceTickCount > 0;
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
				message.startsWith("You drink the wine"))
				{
			int attackDelay = (message.toLowerCase().contains("karambwan")) ? KARAMBWAN_ATTACK_DELAY_TICKS
					: DEFAULT_FOOD_ATTACK_DELAY_TICKS;

			if (attackState == AttackState.DELAYED)
			{
				attackDelayHoldoffTicks += attackDelay;
			}
		}
	}

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
					if (isPlayerAttackingThisTick())
					{
						performAttack();
					}
					break;
				case DELAYED:
					// Don't reset tick counter or tick period.
					break;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		boolean isAttacking = isPlayerAttackingThisTick();
		switch (attackState)
		{
			case NOT_ATTACKING:
				if (isAttacking)
				{
					performAttack(); // Sets state to DELAYED.
				} else
				{
					uiUnshowDebounceTickCount--;
				}
				break;
			case DELAYED:
				if (attackDelayHoldoffTicks <= 0)
				{ // Eligible for a new attack
					if (isAttacking)
					{
						// Found an attack animation. Assume auto attack triggered.
						performAttack();
					} else
					{
						// No attack animation; assume no attack.
						attackState = AttackState.NOT_ATTACKING;
					}
				}
		}

		adjustForRedKeris();
		attackDelayHoldoffTicks--;
	}

	private void adjustForRedKeris()
	{
		int tickCount = client.getTickCount();
		if (RedKerisTarget != null && RedKerisHitsplatTick == tickCount)
		{
			if (!RedKerisHitsplats.isEmpty())
			{
				// The weapon hitsplat is always last, after other hitsplats
				// which occur on the same tick such as from venge or thralls.
				Hitsplat hitsplat = RedKerisHitsplats.get(RedKerisHitsplats.size() - 1);
				if (hitsplat.getAmount() == 0)
				{
					// Miss, reduce this by 4 ticks, not perfect but we can't do much better
					attackDelayHoldoffTicks -= 4;
				}
			}
			// Reset for next attacks
			RedKerisTarget = null;
			RedKerisHitsplatTick = -1;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals("improvedattacktimer"))
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
