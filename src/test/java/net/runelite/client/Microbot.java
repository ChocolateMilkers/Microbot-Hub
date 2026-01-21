package net.runelite.client;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import net.runelite.client.plugins.fishing.FishingPlugin;
import net.runelite.client.plugins.microbot.aiofighter.AIOFighterPlugin;
import net.runelite.client.plugins.microbot.astralrc.AstralRunesPlugin;
<<<<<<< Updated upstream
import net.runelite.client.plugins.microbot.autofishing.AutoFishingPlugin;
import net.runelite.client.plugins.microbot.construction.ConstructionPlugin;
import net.runelite.client.plugins.microbot.zombiepiratelocker.ZombiePirateLockerPlugin;
import net.runelite.client.plugins.microbot.con.ConPlugin;
=======
import net.runelite.client.plugins.microbot.zombiepiratelocker.ZombiePirateLockerPlugin;
import net.runelite.client.plugins.microbot.con.ConPlugin;
import net.runelite.client.plugins.microbot.example.ExamplePlugin;
>>>>>>> Stashed changes

public class Microbot
{

	private static final Class<?>[] debugPlugins = {
<<<<<<< Updated upstream
		AIOFighterPlugin.class, ConstructionPlugin.class, ZombiePirateLockerPlugin.class, ConPlugin.class
=======
		AIOFighterPlugin.class, ZombiePirateLockerPlugin.class, ConPlugin.class
>>>>>>> Stashed changes
	};

    public static void main(String[] args) throws Exception
    {
		List<Class<?>> _debugPlugins = Arrays.stream(debugPlugins).collect(Collectors.toList());
        RuneLiteDebug.pluginsToDebug.addAll(_debugPlugins);
        RuneLiteDebug.main(args);
    }
}
