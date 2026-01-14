package net.runelite.client.plugins.microbot.zombiepiratelocker;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;

public class ZombiePirateLockerOverlay extends OverlayPanel {

    @Inject
    ZombiePirateLockerOverlay(ZombiePirateLockerPlugin plugin) {
        super(plugin);
        setPosition(OverlayPosition.TOP_LEFT);
        setNaughty();
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        panelComponent.getChildren().clear();
        panelComponent.setPreferredSize(new Dimension(200, 100));

        panelComponent.getChildren().add(
                TitleComponent.builder()
                        .text("Zombie Pirate Locker")
                        .color(Color.GREEN)
                        .build()
        );

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Keys used:")
                .right(String.valueOf(ZombiePirateLockerScript.keysUsed))
                .build());

        panelComponent.getChildren().add(LineComponent.builder()
                .left("Status:")
                .right(Microbot.status)
                .build());

        return super.render(graphics);
    }
}