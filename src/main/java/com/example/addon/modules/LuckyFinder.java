package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import java.util.*;

public class LuckyFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> minDelaySec = sgGeneral.add(new IntSetting.Builder()
            .name("min-delay-sec").defaultValue(5).range(1, 300).build());

    private final Setting<Integer> maxDelaySec = sgGeneral.add(new IntSetting.Builder()
            .name("max-delay-sec").defaultValue(30).range(1, 600).build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
            .name("radius-chunks").defaultValue(8).range(1, 16).build());

    private final Setting<Integer> maxFlags = sgGeneral.add(new IntSetting.Builder()
            .name("max-flags").defaultValue(30).range(1, 100).build());

    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-alert").defaultValue(true).build());

    private final Setting<Boolean> soundAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("sound-alert").defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
            .name("color").defaultValue(new SettingColor(0, 255, 70, 75)).build());

    private final Random random = new Random();
    private final Map<Long, Long> flagged = new LinkedHashMap<>();
    private Object trackedWorld;
    private int nextDelay;

    public LuckyFinder() {
        super(AddonTemplate.CATEGORY, "lucky-finder",
              "Randomly flags nearby chunks. Follow your luck.");
    }

    @Override
    public void onActivate() {
        flagged.clear();
        trackedWorld = mc.world;
        nextDelay = randomDelay();
    }

    @Override
    public void onDeactivate() { flagged.clear(); }

    private int randomDelay() {
        int min = minDelaySec.get();
        int max = Math.max(min, maxDelaySec.get());
        return (min + random.nextInt(max - min + 1)) * 20;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (trackedWorld != mc.world) {
            flagged.clear();
            trackedWorld = mc.world;
        }

        while (flagged.size() >= maxFlags.get()) {
            Iterator<Long> it = flagged.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }

        if (--nextDelay > 0) return;
        nextDelay = randomDelay();
        tryFlag();
    }

    private void tryFlag() {
        ChunkPos center = mc.player.getChunkPos();
        int r = radius.get();

        for (int attempts = 0; attempts < 32; attempts++) {
            int cx = center.x + random.nextInt(-r, r + 1);
            int cz = center.z + random.nextInt(-r, r + 1);
            if (!mc.world.isChunkLoaded(cx, cz)) continue;

            long key = ChunkPos.toLong(cx, cz);
            if (flagged.containsKey(key)) continue;

            flagged.put(key, System.currentTimeMillis());
            alert(cx, cz);
            return;
        }
    }

    private void alert(int cx, int cz) {
        int bx = (cx << 4) + 8;
        int bz = (cz << 4) + 8;
        if (chatAlert.get() && mc.player != null) {
            mc.player.sendMessage(Text.literal(
                "§a[LuckyFinder] §fFlagged §e" + bx + ", " + bz
            ), false);
        }
        if (soundAlert.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1f, 1.5f);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (flagged.isEmpty()) return;
        SettingColor c = color.get();
        for (long key : flagged.keySet()) {
            int cx = ChunkPos.getPackedX(key);
            int cz = ChunkPos.getPackedZ(key);
            double x = cx << 4, z = cz << 4;
            event.renderer.box(x, 63, z, x + 16, 63.15, z + 16,
                    c, c, ShapeMode.Both, 0);
        }
    }
}