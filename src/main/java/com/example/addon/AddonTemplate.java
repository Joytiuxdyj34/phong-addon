package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.modules.ActivityInspector;
import com.example.addon.modules.AntiVanish;
import com.example.addon.modules.ChunkFinderP;
import com.example.addon.modules.LuckyFinder;
import com.example.addon.modules.PlayerBypass;
import com.example.addon.modules.StorageEspP;
import com.example.addon.modules.SusChunk;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("SkidAddon");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Phongaddon");

        Modules.get().add(new ActivityInspector());
        Modules.get().add(new AntiVanish());
        Modules.get().add(new ChunkFinderP());
        Modules.get().add(new LuckyFinder());
        Modules.get().add(new PlayerBypass());
        Modules.get().add(new StorageEspP());
        Modules.get().add(new SusChunk());


        Commands.add(new CommandExample());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
    }
}
