package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.fishing.FishingPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.astralrc.AstralRunesPlugin;
import net.runelite.client.plugins.microbot.autofishing.AutoFishingPlugin;
import net.runelite.client.plugins.microbot.construction.ConstructionPlugin;
import net.runelite.client.plugins.microbot.zombiepiratelocker.ZombiePirateLockerPlugin;
import net.runelite.client.plugins.microbot.con.ConPlugin;

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
		AIOFighterPlugin.class, ConstructionPlugin.class, ZombiePirateLockerPlugin.class, ConPlugin.class
	};

    public static void main(String[] args) throws Exception
    {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}
