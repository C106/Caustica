package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaConfig;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Dedicated runtime settings screen for the ray-marched Overworld cloud layer. */
public final class RtCloudSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE = Component.translatable("caustica.options.rt.cloudSettings.title");

    public RtCloudSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        this.list.addSmall(RtVideoOptions.cloudOptions());
    }

    @Override
    public void removed() {
        super.removed();
        CausticaConfig.save();
    }
}
