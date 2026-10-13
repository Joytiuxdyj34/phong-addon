package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import java.util.*;

public class AntiVanish extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> chatAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("chat-alert").defaultValue(true).build());

    private final Setting<Boolean> soundAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("sound-alert").defaultValue(true).build());

    private final Setting<Boolean> checkWorld = sgGeneral.add(new BoolSetting.Builder()
            .name("check-still-in-world")
            .description("Only alert if the entity is still loaded")
            .defaultValue(true).build());

    private final Setting<Integer> minRange = sgGeneral.add(new IntSetting.Builder()
            .name("min-range-blocks")
            .description("Only alert if target is within this distance (0 = no limit)")
            .defaultValue(128).range(0, 512).sliderRange(0, 512).build());

    private final Setting<String> prefix = sgGeneral.add(new StringSetting.Builder()
            .name("prefix").defaultValue("§c[AntiVanish]").build());

    public AntiVanish() {
        super(AddonTemplate.CATEGORY, "anti-vanish",
              "Alerts in chat when a player vanishes from TAB but remains in world");
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof PlayerRemoveS2CPacket remove) {
            for (UUID id : remove.profileIds()) {
                if (id == null || id.equals(mc.player.getUuid())) continue;
                check(id, null);
            }
        }

        if (event.packet instanceof PlayerListS2CPacket list) {
            if (!list.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED)) return;
            for (var entry : list.getEntries()) {
                if (entry.listed() || entry.profileId() == null) continue;
                if (entry.profileId().equals(mc.player.getUuid())) continue;
                String name = entry.profile() != null ? entry.profile().name() : null;
                check(entry.profileId(), name);
            }
        }
    }

    private void check(UUID id, String fallbackName) {
        PlayerEntity target = mc.world.getPlayerByUuid(id);

        if (checkWorld.get()) {
            if (target == null || target.isRemoved()) return;
            int maxR = minRange.get();
            if (maxR > 0 && mc.player.squaredDistanceTo(target) > (double) maxR * maxR) return;
        }

        String name = target != null ? target.getName().getString()
                     : (fallbackName != null ? fallbackName : id.toString());

        alert(name, target);
    }

    private void alert(String name, PlayerEntity target) {
        String where = target != null
                ? String.format(" §7@ %.0f blocks", Math.sqrt(mc.player.squaredDistanceTo(target)))
                : "";

        if (chatAlert.get() && mc.player != null) {
            mc.player.sendMessage(
                Text.literal(prefix.get() + " §f" + name + " §7vanished (still in world)" + where),
                false
            );
        }
        if (soundAlert.get() && mc.player != null) {
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 2f);
        }
    }
}