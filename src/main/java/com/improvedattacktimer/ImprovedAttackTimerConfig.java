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
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(ImprovedAttackTimerConfig.GROUP)
public interface ImprovedAttackTimerConfig extends Config
{
	String GROUP = "improvedattacktimer";
	@ConfigSection(
		description = "Change attack tick cooldown settings",
		name = "Attack Cooldown Tick Settings",
		position = 1
	)
	String TickNumberSettings = "Attack Cooldown Tick Settings";

	@ConfigItem(
			position = 1,
			keyName = "showTick",
			name = "Show Attack Cooldown Ticks",
			description = "Shows number of ticks until next attack",
			section = TickNumberSettings
	)
	default boolean showTick() { return false; }

	@Range(
			min = 8,
			max = 50
	)
	@ConfigItem(
			position = 4,
			keyName = "fontSize",
			name = "Font Size (Overhead Tick Only)",
			description = "Change the font size of the overhead attack cooldown ticks",
			section = TickNumberSettings
	)
	default int fontSize() { return 15; }

	@ConfigItem(
			position = 5,
			keyName = "countColor",
			name = "Tick Number Color",
			description = "Configures the color of tick number",
			section = TickNumberSettings
	)
	default Color NumberColor() { return Color.CYAN; }

	@ConfigItem(
			position = 6,
			keyName = "fontType",
			name = "Font Type",
			description = "Change the font of the Tick Number",
			section = TickNumberSettings
	)
	default FontTypes fontType() { return FontTypes.REGULAR; }

	@ConfigSection(
			name = "Attack Bar",
			description = "Shows a loading bar representing the attack cool down",
			position = 7
	)
	String AttackBarSettings = "Attack Cooldown Bar Settings";

	@ConfigItem(
			position = 1,
			keyName = "attackBar",
			name = "Show Attack Bar",
			description = "Show the attack bar",
			section = AttackBarSettings
	)
	default boolean showBar() { return false; }

	@ConfigItem(
			position = 2,
			keyName = "attackBarHeightOffset",
			name = "Height Offset",
			description = "Height offset for the bar from top of player model",
			section = AttackBarSettings
	)
	default int heightOffset() { return 0; }

	@ConfigItem(
			position = 3,
			keyName = "attackBarEmpties",
			name = "Empties Before Attack",
			description = "Controls whether the attack bar will fully empty before a new attack can occur",
			section = AttackBarSettings
	)
	default boolean barEmpties() { return true; }

	@ConfigItem(
			position = 4,
			keyName = "attackBarFills",
			name = "Fills Before Attack",
			description = "Controls whether the attack bar will fill completely after an attack",
			section = AttackBarSettings
	)
	default boolean barFills() { return true; }

	@ConfigItem(
			position = 5,
			keyName = "attackBarDirection",
			name = "Attack Bar Drains",
			description = "Controls whether the attack bar will fill or drain as a cooldown",
			section = AttackBarSettings
	)
	default boolean barDirection() { return true; }
}
