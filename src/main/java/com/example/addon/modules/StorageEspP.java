package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.*;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

public class StorageEspP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chests = sgGeneral.add(new BoolSetting.Builder()
            .name("chests").defaultValue(true).build());
    private final Setting<Boolean> enderChests = sgGeneral.add(new BoolSetting.Builder()
            .name("ender-chests").defaultValue(true).build());
    private final Setting<Boolean> shulkers = sgGeneral.add(new BoolSetting.Builder()
            .name("shulkers").defaultValue(true).build());
    private final Setting<Boolean> spawners = sgGeneral.add(new BoolSetting.Builder()
            .name("spawners").defaultValue(true).build());
    private final Setting<Boolean> furnaces = sgGeneral.add(new BoolSetting.Builder()
            .name("furnaces").defaultValue(true).build());
    private final Setting<Boolean> barrels = sgGeneral.add(new BoolSetting.Builder()
            .name("barrels").defaultValue(true).build());
    private final Setting<Boolean> hoppers = sgGeneral.add(new BoolSetting.Builder()
            .name("hoppers").defaultValue(true).build());
    private final Setting<Boolean> dispensers = sgGeneral.add(new BoolSetting.Builder()
            .name("dispensers").defaultValue(true).build());

    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
            .name("alpha").defaultValue(100).range(0, 255).sliderRange(0, 255).build());
    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode").defaultValue(ShapeMode.Both).build());

    private final List<BlockPos> targets = new ArrayList<>();
    private Object scannedWorld;
    private int cooldown;

    public StorageEsp() {
        super(AddonTemplate.CATEGORY, "storage-esp",
              "Highlights storage blocks through walls");
    }

    @Override
    public void onActivate() { targets.clear(); scannedWorld = null; cooldown = 0; }

    @Override
    public void onDeactivate() { targets.clear(); scannedWorld = null; }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) { targets.clear(); return; }
        if (scannedWorld != mc.world) {
            targets.clear();
            scannedWorld = mc.world;
            cooldown = 0;
        }
        if (--cooldown > 0) return;
        refreshTargets();
        cooldown = 10;
    }

    private void refreshTargets() {
        targets.clear();
        ClientWorld world = mc.world;
        int viewDist = mc.options.getClampedViewDistance();
        ChunkPos playerChunk = mc.player.getChunkPos();

        for (int cx = playerChunk.x - viewDist; cx <= playerChunk.x + viewDist; cx++) {
            for (int cz = playerChunk.z - viewDist; cz <= playerChunk.z + viewDist; cz++) {
                if (!world.isChunkLoaded(cx, cz)) continue;
                WorldChunk chunk = world.getChunk(cx, cz);
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be.isRemoved()) continue;
                    if (!matches(be)) continue;
                    targets.add(be.getPos().toImmutable());
                }
            }
        }
    }

    private boolean matches(BlockEntity be) {
        if (be instanceof EnderChestBlockEntity) return enderChests.get();
        if (be instanceof ChestBlockEntity) return chests.get();
        if (be instanceof ShulkerBoxBlockEntity) return shulkers.get();
        if (be instanceof MobSpawnerBlockEntity) return spawners.get();
        if (be instanceof AbstractFurnaceBlockEntity) return furnaces.get();
        if (be instanceof BarrelBlockEntity) return barrels.get();
        if (be instanceof HopperBlockEntity) return hoppers.get();
        if (be instanceof DispenserBlockEntity) return dispensers.get();
        return false;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || targets.isEmpty()) return;
        int a = alpha.get();
        SettingColor color = new SettingColor(255, 255, 0, a);

        for (BlockPos pos : targets) {
            event.renderer.box(
                    pos.getX() + 0.05, pos.getY() + 0.05, pos.getZ() + 0.05,
                    pos.getX() + 0.95, pos.getY() + 0.95, pos.getZ() + 0.95,
                    color, color, shapeMode.get(), 0);
        }
    }
}