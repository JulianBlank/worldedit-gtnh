/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.command.tool.brush;

import java.util.Random;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BaseItem;
import com.sk89q.worldedit.blocks.ItemID;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.factory.RegionFactory;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.World;

/**
 * Applies bone meal to the blocks inside a region, as if a player had
 * right-clicked every one of those blocks while holding bone meal.
 *
 * <p>
 * Because the bone meal is applied through the world's item use logic, the
 * normal bone meal effects apply. Blocks added by other mods are
 * supported as long as they implement Minecraft's "growable" behaviour.
 * </p>
 */
public class BonemealBrush implements Brush {

    /**
     * Bone meal: an ink sac (dye) with a data value of 15.
     */
    public static final BaseItem BONE_MEAL = new BaseItem(ItemID.INK_SACK, (short) 15);

    private final RegionFactory regionFactory;
    private final double density;
    private final Random random = new Random();

    /**
     * Create a new bone meal brush.
     *
     * @param regionFactory the factory used to create the region that is bonemealed
     * @param density       the chance, between 0 and 1, that a block is bonemealed
     */
    public BonemealBrush(RegionFactory regionFactory, double density) {
        this.regionFactory = regionFactory;
        this.density = density;
    }

    @Override
    public void build(EditSession editSession, Vector position, Pattern pattern, double size)
        throws MaxChangedBlocksException {
        if (density <= 0) {
            return;
        }

        Region region = regionFactory.createCenteredAt(position, size);
        World world = editSession.getWorld();

        for (Vector point : region) {
            if (density < 1 && random.nextDouble() >= density) {
                continue;
            }

            if (!canGrow(editSession, point)) {
                continue;
            }

            world.useItem(point, BONE_MEAL, Direction.UP);
        }
    }

    /**
     * Test whether it is worth applying bone meal to a position.
     *
     * <p>
     * Bone meal only has an effect on blocks that can grow, and a block that
     * is buried under another block cannot grow into the space above it, so
     * everything else is skipped to avoid pointless work.
     * </p>
     *
     * @param editSession the edit session
     * @param position    the position
     * @return true if bone meal may have an effect
     */
    private boolean canGrow(EditSession editSession, Vector position) {
        BaseBlock block = editSession.getBlock(position);

        if (block.isAir()) {
            return false;
        }

        return editSession.getBlock(position.add(0, 1, 0))
            .isAir();
    }

}
