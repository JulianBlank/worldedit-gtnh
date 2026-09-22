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

package com.sk89q.worldedit.function.generator;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.function.mask.ExistingBlockMask;
import com.sk89q.worldedit.function.mask.Mask2D;
import com.sk89q.worldedit.function.mask.NoiseFilter2D;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.noise.RandomNoise;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.FlatRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.Regions;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.registry.WorldData;

/**
 * Spreads copies of a clipboard over a region, much like a forest is planted.
 *
 * <p>
 * Copies are placed one per column of the region and are centred on that
 * column. They can either sit on the surface of the terrain or float at a
 * random height inside the region, and they can optionally be rotated to a
 * random one of the four 90 degree rotations. Only the blocks of a copy are
 * measured and placed: the air around them in the clipboard is ignored.
 * </p>
 */
public class ClipboardSpreadGenerator {

    /**
     * The number of 90 degree rotations a copy can be given.
     */
    private static final int ROTATIONS = 4;

    /**
     * How many blocks have to follow below a block before it counts as ground
     * when its ID is not one of the configured ground blocks. "More than three
     * blocks in a row" means four.
     */
    private static final int MIN_GROUND_DEPTH = 4;

    /**
     * The rotation baked into a copy one step at a time.
     */
    private static final AffineTransform ROTATION_STEP = new AffineTransform().rotateY(90);

    private static final int NOT_FOUND = Integer.MIN_VALUE;

    private final EditSession editSession;
    private final WorldData worldData;
    private final Clipboard clipboard;
    private final boolean rotate;
    private final boolean noCollide;
    private final Set<Integer> groundBlocks;
    private final Random random = new Random();
    private final List<CuboidRegion> placed = new ArrayList<CuboidRegion>();
    private final Variant[] variants = new Variant[ROTATIONS];
    private int count;

    /**
     * Create a new spread generator.
     *
     * @param editSession  the edit session the copies are placed in
     * @param holder       the clipboard to spread
     * @param groundBlocks the block IDs that count as ground
     * @param rotate       true to give every copy a random 90 degree rotation
     * @param noCollide    true to skip copies that would overlap an earlier copy
     */
    public ClipboardSpreadGenerator(EditSession editSession, ClipboardHolder holder, Set<Integer> groundBlocks,
        boolean rotate, boolean noCollide) {
        checkNotNull(editSession);
        checkNotNull(holder);
        checkNotNull(groundBlocks);
        this.editSession = editSession;
        this.worldData = editSession.getWorld()
            .getWorldData();
        this.clipboard = holder.getClipboard();
        this.groundBlocks = groundBlocks;
        this.rotate = rotate;
        this.noCollide = noCollide;
    }

    /**
     * Get the number of copies placed so far.
     *
     * @return the number of copies
     */
    public int getCount() {
        return count;
    }

    /**
     * Spread copies of the clipboard over the given region.
     *
     * @param region    the region to spread the copies over
     * @param density   the chance, between 0 and 1, that a column receives a copy
     * @param onSurface true to place the copies on the surface of the terrain,
     *                  false to place them at a random height in the region
     * @return the number of copies that were placed
     * @throws WorldEditException thrown if a copy could not be placed
     */
    public int spread(Region region, double density, boolean onSurface) throws WorldEditException {
        if (density <= 0) {
            return 0;
        }

        FlatRegion columns = Regions.asFlatRegion(region);
        int minimumY = Regions.minimumBlockY(region);
        int maximumY = Regions.maximumBlockY(region);
        Mask2D densityFilter = new NoiseFilter2D(new RandomNoise(), density > 1 ? 1 : density);

        for (Vector2D column : columns.asFlatRegion()) {
            if (!densityFilter.test(column)) {
                continue;
            }

            if (onSurface) {
                int groundY = findGround(column, minimumY, maximumY);

                if (groundY != NOT_FOUND) {
                    placeOnSurface(column, groundY);
                }
            } else {
                placeInVolume(region);
            }
        }

        return count;
    }

    /**
     * Find the highest block of a column that can be built on.
     *
     * <p>
     * A block can be built on when its ID is one of the ground blocks. If the
     * column contains none of those blocks, the highest block of the column is
     * used when more than three blocks in a row follow below it, which keeps
     * buildings off things like a lone leaf or a floating block.
     * </p>
     *
     * @param column   the column
     * @param minimumY the lowest Y to look at
     * @param maximumY the highest Y to look at
     * @return the Y of the ground, or {@link #NOT_FOUND}
     */
    private int findGround(Vector2D column, int minimumY, int maximumY) {
        int highest = NOT_FOUND;

        for (int y = maximumY; y >= minimumY; --y) {
            int type = blockType(column, y);

            if (type == BlockID.AIR) {
                continue;
            }

            if (highest == NOT_FOUND) {
                highest = y;
            }

            if (groundBlocks.contains(type)) {
                return y;
            }
        }

        if (highest != NOT_FOUND && solidRun(column, highest) >= MIN_GROUND_DEPTH) {
            return highest;
        }

        return NOT_FOUND;
    }

    /**
     * Count how many blocks in a row are not air, starting at a position and
     * working downwards.
     *
     * @param column the column
     * @param fromY  the Y to start at
     * @return the number of blocks
     */
    private int solidRun(Vector2D column, int fromY) {
        int depth = 0;

        for (int y = fromY; y >= 0; --y) {
            if (blockType(column, y) == BlockID.AIR) {
                break;
            }

            depth++;
        }

        return depth;
    }

    /**
     * Get the type of the block at a position in a column.
     *
     * @param column the column
     * @param y      the Y
     * @return the block type
     */
    private int blockType(Vector2D column, int y) {
        return editSession.getLazyBlock(column.toVector(y))
            .getType();
    }

    /**
     * Place a copy on top of the ground of the given column.
     *
     * @param column  the column
     * @param groundY the Y of the ground block
     * @throws WorldEditException thrown if the copy could not be placed
     */
    private void placeOnSurface(Vector2D column, int groundY) throws WorldEditException {
        Variant variant = variant(pickRotations());

        int minX = column.getBlockX() - (variant.size.getBlockX() - 1) / 2;
        int minZ = column.getBlockZ() - (variant.size.getBlockZ() - 1) / 2;

        place(variant, new Vector(minX, groundY + 1, minZ));
    }

    /**
     * Place a copy at a random position inside the given region.
     *
     * <p>
     * The copy is kept inside the region whenever it is small enough to fit.
     * </p>
     *
     * @param region the region
     * @throws WorldEditException thrown if the copy could not be placed
     */
    private void placeInVolume(Region region) throws WorldEditException {
        Variant variant = variant(pickRotations());

        Vector minimum = region.getMinimumPoint();
        Vector maximum = region.getMaximumPoint();

        int minX = randomStart(minimum.getBlockX(), maximum.getBlockX(), variant.size.getBlockX());
        int minY = randomStart(minimum.getBlockY(), maximum.getBlockY(), variant.size.getBlockY());
        int minZ = randomStart(minimum.getBlockZ(), maximum.getBlockZ(), variant.size.getBlockZ());

        place(variant, new Vector(minX, minY, minZ));
    }

    /**
     * Pick a random coordinate for the lowest corner of a copy of the given
     * size, so that the copy stays inside the given range where possible.
     *
     * @param minimum the lowest coordinate of the range
     * @param maximum the highest coordinate of the range
     * @param size    the size of the copy
     * @return a random coordinate
     */
    private int randomStart(int minimum, int maximum, int size) {
        int last = maximum - size + 1;

        if (last < minimum) {
            last = minimum;
        }

        return minimum + random.nextInt(last - minimum + 1);
    }

    /**
     * Place a copy so that the lowest corner of its blocks ends up at the
     * given position.
     *
     * @param variant the rotation to place
     * @param anchor  the position of the lowest corner of the copy
     * @throws WorldEditException thrown if the copy could not be placed
     */
    private void place(Variant variant, Vector anchor) throws WorldEditException {
        Vector maximum = anchor.add(variant.size.subtract(1, 1, 1));

        if (noCollide && collides(anchor, maximum)) {
            return;
        }

        ForwardExtentCopy copy = new ForwardExtentCopy(
            variant.clipboard,
            variant.region,
            variant.origin,
            editSession,
            anchor.subtract(variant.contentMinimum.subtract(variant.origin)));
        // Air is skipped so that the copies add to the world instead of
        // cutting holes in it where they overlap.
        copy.setSourceMask(new ExistingBlockMask(variant.clipboard));
        Operations.completeLegacy(copy);

        placed.add(new CuboidRegion(anchor, maximum));
        count++;
    }

    /**
     * Pick the number of 90 degree rotations for the next copy.
     *
     * @return the number of rotations
     */
    private int pickRotations() {
        return rotate ? random.nextInt(ROTATIONS) : 0;
    }

    /**
     * Get the clipboard for the given number of rotations, rotating it once
     * and caching the result the first time that it is needed.
     *
     * @param rotations the number of 90 degree rotations
     * @return the rotated clipboard
     * @throws WorldEditException thrown if the clipboard could not be rotated
     */
    private Variant variant(int rotations) throws WorldEditException {
        Variant variant = variants[rotations];

        if (variant == null) {
            Clipboard source = rotations == 0 ? clipboard : bake(variant(rotations - 1).clipboard);
            variant = new Variant(source);
            variants[rotations] = variant;
        }

        return variant;
    }

    /**
     * Bake a single 90 degree rotation into a new copy of a clipboard.
     *
     * <p>
     * Every rotation is applied on its own, because some mods only rotate
     * their blocks correctly when they are rotated one step at a time.
     * </p>
     *
     * @param source the clipboard to rotate
     * @return the rotated clipboard
     * @throws WorldEditException thrown if the clipboard could not be rotated
     */
    private Clipboard bake(Clipboard source) throws WorldEditException {
        Region region = source.getRegion();
        Vector origin = source.getOrigin();

        Vector first = ROTATION_STEP.apply(
            region.getMinimumPoint()
                .subtract(origin));
        Vector second = ROTATION_STEP.apply(
            region.getMaximumPoint()
                .subtract(origin));

        BlockArrayClipboard baked = new BlockArrayClipboard(
            new CuboidRegion(
                origin.add(Vector.getMinimum(first, second)),
                origin.add(Vector.getMaximum(first, second))));
        baked.setOrigin(origin);

        Extent extent = new BlockTransformExtent(
            new BlocksOnlyExtent(source),
            ROTATION_STEP,
            worldData.getBlockRegistry(),
            worldData.getBlockTransformHook());

        ForwardExtentCopy copy = new ForwardExtentCopy(extent, region, origin, baked, origin);
        copy.setTransform(ROTATION_STEP);
        Operations.completeLegacy(copy);

        return baked;
    }

    /**
     * Test whether a copy would overlap one of the copies placed earlier.
     *
     * @param minimum the lowest corner of the copy
     * @param maximum the highest corner of the copy
     * @return true if the copy collides with an earlier copy
     */
    private boolean collides(Vector minimum, Vector maximum) {
        for (CuboidRegion region : placed) {
            Vector otherMinimum = region.getMinimumPoint();
            Vector otherMaximum = region.getMaximumPoint();

            if (minimum.getBlockX() <= otherMaximum.getBlockX() && maximum.getBlockX() >= otherMinimum.getBlockX()
                && minimum.getBlockY() <= otherMaximum.getBlockY()
                && maximum.getBlockY() >= otherMinimum.getBlockY()
                && minimum.getBlockZ() <= otherMaximum.getBlockZ()
                && maximum.getBlockZ() >= otherMinimum.getBlockZ()) {
                return true;
            }
        }

        return false;
    }

    /**
     * A clipboard rotated a fixed number of times, together with the box of
     * its blocks.
     */
    private static final class Variant {

        private final Clipboard clipboard;
        private final Region region;
        private final Vector origin;
        private final Vector contentMinimum;
        private final Vector size;

        private Variant(Clipboard clipboard) {
            this.clipboard = clipboard;
            this.region = clipboard.getRegion();
            this.origin = clipboard.getOrigin();

            Vector minimum = null;
            Vector maximum = null;

            for (Vector position : region) {
                if (clipboard.getBlock(position)
                    .isAir()) {
                    continue;
                }

                if (minimum == null) {
                    minimum = position;
                    maximum = position;
                } else {
                    minimum = Vector.getMinimum(minimum, position);
                    maximum = Vector.getMaximum(maximum, position);
                }
            }

            // An empty clipboard has no blocks, so fall back to its region.
            if (minimum == null) {
                minimum = region.getMinimumPoint();
                maximum = region.getMaximumPoint();
            }

            this.contentMinimum = minimum;
            this.size = maximum.subtract(minimum)
                .add(1, 1, 1);
        }

    }

    /**
     * An extent that does not expose the entities of the extent it wraps.
     *
     * <p>
     * Copies are block copies only, so that a clipboard containing entities
     * does not spawn a copy of those entities for every copy that is placed.
     * </p>
     */
    private static final class BlocksOnlyExtent extends AbstractDelegateExtent {

        private BlocksOnlyExtent(Extent extent) {
            super(extent);
        }

        @Override
        public List<? extends Entity> getEntities() {
            return Collections.emptyList();
        }

        @Override
        public List<? extends Entity> getEntities(Region region) {
            return Collections.emptyList();
        }

    }

}
