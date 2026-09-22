/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.forge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.S2APacketParticles;
import net.minecraft.server.MinecraftServer;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.regions.Region;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Renders enabled selections as temporary client-side particles. */
public final class SelectionVisualizer {

    private static final SelectionVisualizer INSTANCE = new SelectionVisualizer();
    private static final int REFRESH_INTERVAL = 5;
    private static final int MAX_PARTICLES = 4096;
    private static final double NEAR_PARTICLE_SPACING = 1.5;
    private static final double FAR_PARTICLE_SPACING = 0.75;
    private static final double DISTANCE_SCALE = 32.0;

    private final Set<UUID> enabled = new HashSet<UUID>();
    private int ticks;

    private SelectionVisualizer() {}

    public static SelectionVisualizer getInstance() {
        return INSTANCE;
    }

    public boolean toggle(UUID playerId) {
        if (enabled.remove(playerId)) {
            return false;
        }

        enabled.add(playerId);
        return true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++ticks < REFRESH_INTERVAL) {
            return;
        }
        ticks = 0;

        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            return;
        }

        List<?> players = server.getConfigurationManager().playerEntityList;
        Set<UUID> online = new HashSet<UUID>();
        for (Object entry : players) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            online.add(player.getUniqueID());
            if (enabled.contains(player.getUniqueID())) {
                render(player);
            }
        }
        enabled.retainAll(online);
    }

    private void render(EntityPlayerMP player) {
        LocalSession session = ForgeWorldEdit.inst.getSession(player);
        Region region;
        try {
            region = session.getSelection(ForgeWorldEdit.inst.getWorld(player.worldObj));
        } catch (IncompleteRegionException e) {
            return;
        }

        Vector min = region.getMinimumPoint();
        Vector max = region.getMaximumPoint()
            .add(1, 1, 1);
        int[] remaining = { MAX_PARTICLES };

        drawEdge(player, min.getX(), min.getY(), min.getZ(), max.getX(), min.getY(), min.getZ(), remaining);
        drawEdge(player, min.getX(), min.getY(), max.getZ(), max.getX(), min.getY(), max.getZ(), remaining);
        drawEdge(player, min.getX(), max.getY(), min.getZ(), max.getX(), max.getY(), min.getZ(), remaining);
        drawEdge(player, min.getX(), max.getY(), max.getZ(), max.getX(), max.getY(), max.getZ(), remaining);
        drawEdge(player, min.getX(), min.getY(), min.getZ(), min.getX(), max.getY(), min.getZ(), remaining);
        drawEdge(player, max.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), min.getZ(), remaining);
        drawEdge(player, min.getX(), min.getY(), max.getZ(), min.getX(), max.getY(), max.getZ(), remaining);
        drawEdge(player, max.getX(), min.getY(), max.getZ(), max.getX(), max.getY(), max.getZ(), remaining);
        drawEdge(player, min.getX(), min.getY(), min.getZ(), min.getX(), min.getY(), max.getZ(), remaining);
        drawEdge(player, max.getX(), min.getY(), min.getZ(), max.getX(), min.getY(), max.getZ(), remaining);
        drawEdge(player, min.getX(), max.getY(), min.getZ(), min.getX(), max.getY(), max.getZ(), remaining);
        drawEdge(player, max.getX(), max.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(), remaining);
    }

    private void drawEdge(EntityPlayerMP player, double startX, double startY, double startZ, double endX, double endY,
        double endZ, int[] remaining) {
        double length = Math.sqrt(Math.pow(endX - startX, 2) + Math.pow(endY - startY, 2) + Math.pow(endZ - startZ, 2));
        double midpointX = (startX + endX) / 2;
        double midpointY = (startY + endY) / 2;
        double midpointZ = (startZ + endZ) / 2;
        double distance = Math.sqrt(player.getDistanceSq(midpointX, midpointY, midpointZ));
        int particleCount = Math.min(4, 1 + (int) (distance / DISTANCE_SCALE));
        double spacing = Math.max(
            FAR_PARTICLE_SPACING,
            NEAR_PARTICLE_SPACING - Math.min(distance, DISTANCE_SCALE * 2) / DISTANCE_SCALE * 0.375);
        int count = Math.min(remaining[0] / particleCount, Math.max(1, (int) Math.ceil(length / spacing)) + 1);

        for (int i = 0; i < count; i++) {
            double fraction = count == 1 ? 0 : (double) i / (count - 1);
            sendParticle(
                player,
                startX + (endX - startX) * fraction,
                startY + (endY - startY) * fraction,
                startZ + (endZ - startZ) * fraction,
                particleCount);
        }
        remaining[0] -= count * particleCount;
    }

    private void sendParticle(EntityPlayerMP player, double x, double y, double z, int count) {
        player.playerNetServerHandler
            .sendPacket(new S2APacketParticles("flame", (float) x, (float) y, (float) z, 0, 0, 0, 0, count));
    }
}
