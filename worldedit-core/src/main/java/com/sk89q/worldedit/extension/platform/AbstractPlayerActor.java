/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.extension.platform;

import com.fastasyncworldedit.core.Fawe;
import com.fastasyncworldedit.core.configuration.Caption;
import com.fastasyncworldedit.core.configuration.Settings;
import com.fastasyncworldedit.core.internal.exception.FaweClipboardVersionMismatchException;
import com.fastasyncworldedit.core.internal.exception.FaweException;
import com.fastasyncworldedit.core.math.MutableBlockVector3;
import com.fastasyncworldedit.core.regions.FaweMaskManager;
import com.fastasyncworldedit.core.util.MainUtil;
import com.fastasyncworldedit.core.util.TaskManager;
import com.fastasyncworldedit.core.util.WEManager;
import com.fastasyncworldedit.core.util.task.AsyncNotifyKeyedQueue;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.internal.cui.CUIEvent;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.regions.ConvexPolyhedralRegion;
import com.sk89q.worldedit.regions.CylinderRegion;
import com.sk89q.worldedit.regions.Polygonal2DRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.regions.RegionSelector;
import com.sk89q.worldedit.regions.selector.ConvexPolyhedralRegionSelector;
import com.sk89q.worldedit.regions.selector.CuboidRegionSelector;
import com.sk89q.worldedit.regions.selector.CylinderRegionSelector;
import com.sk89q.worldedit.regions.selector.Polygonal2DRegionSelector;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.util.HandSide;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.TargetBlock;
import com.sk89q.worldedit.util.auth.AuthorizationException;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockCategories;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.sk89q.worldedit.world.gamemode.GameMode;
import com.sk89q.worldedit.world.gamemode.GameModes;
import com.sk89q.worldedit.world.item.ItemType;
import com.sk89q.worldedit.world.item.ItemTypes;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An abstract implementation of both a {@link Actor} and a {@link Player}
 * that is intended for implementations of WorldEdit to use to wrap
 * players that make use of WorldEdit.
 */
public abstract class AbstractPlayerActor implements Actor, Player, Cloneable {

    private static final Logger LOGGER = LogManagerCompat.getLogger();

    //FAWE start
    private final Map<String, Object> meta;
    private final Semaphore clipboardLoading = new Semaphore(1);

    // Queue for async tasks
    private final AtomicInteger runningCount = new AtomicInteger();
    private final AsyncNotifyKeyedQueue asyncNotifyQueue = new AsyncNotifyKeyedQueue(
            (thread, throwable) -> {
                while (throwable.getCause() != null) {
                    throwable = throwable.getCause();
                }
                if (throwable instanceof WorldEditException) {
                    printError(TextComponent.of(throwable.getLocalizedMessage()));
                } else {
                    FaweException fe = FaweException.get(throwable);
                    if (fe != null) {
                        printError(fe.getComponent());
                    } else {
                        LOGGER.error("Error occurred executing player action", throwable);
                    }
                }
            }, this::getUniqueId);

    public AbstractPlayerActor(Map<String, Object> meta) {
        this.meta = meta;
    }

    public AbstractPlayerActor() {
        this(new ConcurrentHashMap<>());
    }
    //FAWE end

    @Override
    public final Extent getExtent() {
        return getWorld();
    }

    /**
     * Returns direction according to rotation. May return null.
     *
     * @param rot yaw
     * @return the direction
     */
    private static Direction getDirection(double rot) {
        if (0 <= rot && rot < 22.5) {
            return Direction.SOUTH;
        } else if (22.5 <= rot && rot < 67.5) {
            return Direction.SOUTHWEST;
        } else if (67.5 <= rot && rot < 112.5) {
            return Direction.WEST;
        } else if (112.5 <= rot && rot < 157.5) {
            return Direction.NORTHWEST;
        } else if (157.5 <= rot && rot < 202.5) {
            return Direction.NORTH;
        } else if (202.5 <= rot && rot < 247.5) {
            return Direction.NORTHEAST;
        } else if (247.5 <= rot && rot < 292.5) {
            return Direction.EAST;
        } else if (292.5 <= rot && rot < 337.5) {
            return Direction.SOUTHEAST;
        } else if (337.5 <= rot && rot < 360.0) {
            return Direction.SOUTH;
        } else {
            return null;
        }
    }

    @Override
    public Map<String, Object> getRawMeta() {
        return meta;
    }

    @Override
    public boolean isHoldingPickAxe() {
        ItemType item = getItemInHand(HandSide.MAIN_HAND).getType();
        return item == ItemTypes.IRON_PICKAXE
                || item == ItemTypes.WOODEN_PICKAXE
                || item == ItemTypes.STONE_PICKAXE
                || item == ItemTypes.DIAMOND_PICKAXE
                || item == ItemTypes.GOLDEN_PICKAXE
                || item == ItemTypes.NETHERITE_PICKAXE;
    }

    @Override
    public void findFreePosition(Location searchPos) {
        Extent world = searchPos.getExtent();

        int worldMinY = world.getMinimumPoint().y();
        int worldMaxY = world.getMaximumPoint().y();

        int x = searchPos.getBlockX();
        int y = Math.max(worldMinY, searchPos.getBlockY());
        int origY = y;
        int z = searchPos.getBlockZ();
        int yPlusSearchHeight = y + WorldEdit.getInstance().getConfiguration().defaultVerticalHeight;
        int maxY = Math.min(worldMaxY, yPlusSearchHeight) + 2;

        byte free = 0;

        BlockVector3 mutablePos = new MutableBlockVector3();
        while (y <= maxY) {
            if (!world.getBlock(mutablePos.setComponents(x, y, z)).getBlockType().getMaterial().isMovementBlocker()) {
                ++free;
            } else {
                free = 0;
            }

            if (free == 2) {
                boolean worked = true;

                if (y - 1 != origY) {
                    worked = trySetPosition(Vector3.at(x + 0.5, y - 2 + 1, z + 0.5));
                }

                if (worked) {
                    return;
                }
            }

            ++y;
        }
    }

    @Override
    public void setOnGround(Location searchPos) {
        Extent world = searchPos.getExtent();

        int worldMinY = world.getMinimumPoint().y();

        int x = searchPos.getBlockX();
        int y = Math.max(worldMinY, searchPos.getBlockY());
        int z = searchPos.getBlockZ();
        int yLessSearchHeight = y - WorldEdit.getInstance().getConfiguration().defaultVerticalHeight;
        int minY = Math.min(worldMinY, yLessSearchHeight) + 2;

        //FAWE start - mutable
        MutableBlockVector3 mutable = new MutableBlockVector3(x, y, z);
        //FAWE end

        while (y >= minY) {
            //FAWE start - mutable
            final BlockState id = world.getBlock(mutable.mutY(y));
            //FAWE end
            if (id.getBlockType().getMaterial().isMovementBlocker()
                    && trySetPosition(Vector3.at(x + 0.5, y + 1, z + 0.5))) {
                return;
            }

            --y;
        }
    }

    @Override
    public void findFreePosition() {
        findFreePosition(getBlockLocation());
    }

    /**
     * Determines if the block at the given location "harms" the player, either by suffocation
     * or other means.
     */
    private boolean isPlayerHarmingBlock(BlockVector3 location) {
        BlockType type = getWorld().getBlock(location).getBlockType();
        return type.getMaterial().isMovementBlocker() || type == BlockTypes.LAVA
                || BlockCategories.FIRE.contains(type);
    }

    /**
     * Check if the location is a good place to leave a standing player.
     *
     * @param location where the player would be placed (not Y offset)
     * @return if the player can stand at the location
     */
    private boolean isLocationGoodForStanding(BlockVector3 location) {
        if (isPlayerHarmingBlock(location.add(0, 1, 0))) {
            return false;
        }
        if (isPlayerHarmingBlock(location)) {
            return false;
        }
        return getWorld().getBlock(location.add(0, -1, 0)).getBlockType().getMaterial()
                .isMovementBlocker();
    }

    @Override
    public boolean ascendLevel() {
        final World world = getWorld();
        final Location pos = getBlockLocation();
        final int x = pos.getBlockX();
        int y = Math.max(world.getMinY(), pos.getBlockY() + 1);
        final int z = pos.getBlockZ();
        int yPlusSearchHeight = y + WorldEdit.getInstance().getConfiguration().defaultVerticalHeight;
        int maxY = Math.min(world.getMaxY(), yPlusSearchHeight) + 2;

        while (y <= maxY) {
            if (isLocationGoodForStanding(BlockVector3.at(x, y, z))
                    && trySetPosition(Vector3.at(x + 0.5, y, z + 0.5))) {
                return true;
            }

            ++y;
        }

        return false;
    }

    @Override
    public boolean descendLevel() {
        final World world = getWorld();
        final Location pos = getBlockLocation();
        final int x = pos.getBlockX();
        int y = Math.max(world.getMinY(), pos.getBlockY() - 1);
        final int z = pos.getBlockZ();
        int yLessSearchHeight = y - WorldEdit.getInstance().getConfiguration().defaultVerticalHeight;
        int minY = Math.min(world.getMinY() + 1, yLessSearchHeight);

        while (y >= minY) {
            if (isLocationGoodForStanding(BlockVector3.at(x, y, z))
                    && trySetPosition(Vector3.at(x + 0.5, y, z + 0.5))) {
                return true;
            }

            --y;
        }

        return false;
    }

    @Override
    public boolean ascendToCeiling(int clearance) {
        return ascendToCeiling(clearance, true);
    }

    @Override
    public boolean ascendToCeiling(int clearance, boolean alwaysGlass) {
        World world = getWorld();
        Location pos = getBlockLocation();
        int x = pos.getBlockX();
        int initialY = Math.max(world.getMinY(), pos.getBlockY());
        int y = Math.max(world.getMinY(), pos.getBlockY() + 2);
        int z = pos.getBlockZ();

        //FAWE start - mutable
        MutableBlockVector3 mutable = new MutableBlockVector3(x, y, z);

        // No free space above
        if (!world.getBlock(mutable).getBlockType().getMaterial().isAir()) {
            //FAWE end
            return false;
        }

        int yPlusSearchHeight = y + WorldEdit.getInstance().getConfiguration().defaultVerticalHeight;
        int maxY = Math.min(world.getMaxY(), yPlusSearchHeight);

        while (y <= maxY) {
            // Found a ceiling!
            //FAWE start - mutable
            if (world.getBlock(mutable.mutY(y)).getBlockType().getMaterial().isMovementBlocker()) {
                //FAWE end
                int platformY = Math.max(initialY, y - 3 - clearance);
                if (platformY < initialY) { // if ==, they already have the given clearance, if <, clearance is too large
                    return false;
                } else if (platformY == initialY) {
                    return false;
                }
                floatAt(x, platformY + 1, z, alwaysGlass);
                return true;
            }

            ++y;
        }

        return false;
    }

    @Override
    public boolean ascendUpwards(int distance) {
        return ascendUpwards(distance, true);
    }

    @Override
    public boolean ascendUpwards(int distance, boolean alwaysGlass) {
        final World world = getWorld();
        final Location pos = getBlockLocation();
        final int x = pos.getBlockX();
        final int initialY = Math.max(world.getMinY(), pos.getBlockY());
        int y = Math.max(world.getMinY(), pos.getBlockY() + 1);
        final int z = pos.getBlockZ();
        final int maxY = Math.min(world.getMaxY() + 1, initialY + distance);

        //FAWE start - mutable
        MutableBlockVector3 mutable = new MutableBlockVector3(x, y, z);
        //FAWE end

        while (y <= world.getMaxY() + 2) {
            //FAWE start - mutable
            if (world.getBlock(mutable.mutY(y)).getBlockType().getMaterial().isMovementBlocker()) {
                //FAWE end
                break; // Hit something
            } else if (y > maxY + 1) {
                break;
            } else if (y == maxY + 1) {
                floatAt(x, y - 1, z, alwaysGlass);
                return true;
            }

            ++y;
        }

        return false;
    }

    @Override
    public void floatAt(int x, int y, int z, boolean alwaysGlass) {
        if (alwaysGlass || !isAllowedToFly()) {
            BlockVector3 spot = BlockVector3.at(x, y - 1, z);
            final World world = getWorld();
            if (!world.getBlock(spot).getBlockType().getMaterial().isMovementBlocker()) {
                try (EditSession session = WorldEdit.getInstance().newEditSession(this)) {
                    session.setBlock(spot, BlockTypes.GLASS.getDefaultState());
                } catch (MaxChangedBlocksException ignored) {
                }
            }
        } else {
            setFlying(true);
        }
        trySetPosition(Vector3.at(x + 0.5, y, z + 0.5));
    }

    /**
     * Check whether the player is allowed to fly.
     *
     * @return true if allowed flight
     */
    protected boolean isAllowedToFly() {
        return false;
    }

    /**
     * Set whether the player is currently flying.
     *
     * @param flying true to fly
     */
    protected void setFlying(boolean flying) {
    }

    @Override
    public Location getBlockOn() {
        final Location location = getLocation();
        return location.setPosition(location.setY(location.y() - 1).toVector().floor());
    }

    @Override
    public Location getBlockTrace(int range, boolean useLastBlock) {
        return getBlockTrace(range, useLastBlock, null);
    }

    @Override
    public Location getBlockTraceFace(int range, boolean useLastBlock) {
        return getBlockTraceFace(range, useLastBlock, null);
    }

    @Override
    public Location getBlockTrace(int range, boolean useLastBlock, @Nullable Mask stopMask) {
        TargetBlock tb = new TargetBlock(this, range, 0.2);
        if (stopMask != null) {
            tb.setStopMask(stopMask);
        }
        return (useLastBlock ? tb.getAnyTargetBlock() : tb.getTargetBlock());
    }

    @Override
    public Location getBlockTraceFace(int range, boolean useLastBlock, @Nullable Mask stopMask) {
        TargetBlock tb = new TargetBlock(this, range, 0.2);
        if (stopMask != null) {
            tb.setStopMask(stopMask);
        }
        return (useLastBlock ? tb.getAnyTargetBlockFace() : tb.getTargetBlockFace());
    }

    @Override
    public Location getBlockTrace(int range) {
        return getBlockTrace(range, false);
    }

    @Override
    public Location getSolidBlockTrace(int range) {
        TargetBlock tb = new TargetBlock(this, range, 0.2);
        return tb.getSolidTargetBlock();
    }

    @Override
    public Direction getCardinalDirection() {
        return getCardinalDirection(0);
    }

    //FAWE start
    @Override
    public Region[] getAllowedRegions() {
        return getAllowedRegions(FaweMaskManager.MaskType.getDefaultMaskType());
    }

    @Override
    public Region[] getAllowedRegions(FaweMaskManager.MaskType type) {
        return WEManager.weManager().getMask(this, type, true);
    }

    @Override
    public Region[] getDisallowedRegions() {
        return getDisallowedRegions(FaweMaskManager.MaskType.getDefaultMaskType());
    }

    @Override
    public Region[] getDisallowedRegions(FaweMaskManager.MaskType type) {
        return WEManager.weManager().getMask(this, type, false);
    }

    @Override
    public Region getLargestRegion() {
        long area = 0;
        Region max = null;
        for (Region region : this.getAllowedRegions()) {
            final long tmp = region.getVolume();
            if (tmp > area) {
                area = tmp;
                max = region;
            }
        }
        return max;
    }

    @Override
    public void setSelection(Region region) {
        RegionSelector selector = switch (region) {
            case ConvexPolyhedralRegion blockVector3s -> new ConvexPolyhedralRegionSelector(blockVector3s);
            case CylinderRegion blockVector3s -> new CylinderRegionSelector(blockVector3s);
            case Polygonal2DRegion blockVector3s -> new Polygonal2DRegionSelector(blockVector3s);
            default -> new CuboidRegionSelector(null, region.getMinimumPoint(), region.getMaximumPoint());
        };
        selector.setWorld(region.getWorld());

        getSession().setRegionSelector(getWorld(), selector);
    }

    @Override
    public void loadClipboardFromDisk() {
        if (!clipboardLoading.tryAcquire()) {
            if (!Fawe.isMainThread()) {
                try {
                    clipboardLoading.acquire();
                    clipboardLoading.release();
                } catch (InterruptedException e) {
                    LOGGER.error("Error waiting for clipboard-on-disk loading for player {}", getName(), e);
                }
            }
            return;
        }

        File file = MainUtil.getFile(
                Fawe.platform().getDirectory(),
                Settings.settings().PATHS.CLIPBOARD + File.separator + getUniqueId() + ".bd"
        );
        try {
            Future<?> fut = Fawe.instance().submitUUIDKeyQueuedTask(getUniqueId(), () -> {
                try {
                    getSession().loadClipboardFromDisk(file);
                } catch (FaweClipboardVersionMismatchException e) {
                    print(e.getComponent());
                } catch (RuntimeException e) {
                    print(Caption.of("fawe.error.clipboard.invalid"));
                    LOGGER.error("Error loading clipboard from disk", e);
                    print(Caption.of("fawe.error.stacktrace"));
                    print(Caption.of("fawe.error.clipboard.load.failure"));
                    print(Caption.of("fawe.error.clipboard.invalid.info", file.getName(), file.length()));
                    print(Caption.of("fawe.error.stacktrace"));
                } catch (Exception e) {
                    print(Caption.of("fawe.error.clipboard.invalid"));
                    LOGGER.error("Error loading clipboard from disk", e);
                    print(Caption.of("fawe.error.stacktrace"));
                    print(Caption.of("fawe.error.no-failure"));
                    print(Caption.of("fawe.error.clipboard.invalid.info", file.getName(), file.length()));
                    print(Caption.of("fawe.error.stacktrace"));
                } finally {
                    clipboardLoading.release();
                }
            });
            if (Fawe.isMainThread()) {
                return;
            }
            fut.get();
        } catch (Exception e) {
            LOGGER.error("Error loading clipboard from disk", e);
            print(Caption.of("fawe.error.clipboard.load.failure"));
        }
    }
    //FAWE end

    @Override
    public Direction getCardinalDirection(int yawOffset) {
        final Location location = getLocation();
        if (location.getPitch() > 67.5) {
            return Direction.DOWN;
        }
        if (location.getPitch() < -67.5) {
            return Direction.UP;
        }

        // From hey0's code
        double rot = (location.getYaw() + yawOffset) % 360; //let's use real yaw now
        if (rot < 0) {
            rot += 360.0;
        }
        return getDirection(rot);
    }

    @Override
    public BaseBlock getBlockInHand(HandSide handSide) throws WorldEditException {
        final ItemType typeId = getItemInHand(handSide).getType();
        if (typeId.hasBlockType()) {
            return typeId.getBlockType().getDefaultState().toBaseBlock();
        } else {
            //FAWE start
            return BlockTypes.AIR.getDefaultState().toBaseBlock(); // FAWE returns air here
            //FAWE end
        }
    }

    private boolean canPassThroughBlock(Location curBlock) {
        BlockVector3 blockPos = curBlock.toVector().toBlockPoint();
        BlockState block = curBlock.getExtent().getBlock(blockPos);
        return !block.getBlockType().getMaterial().isMovementBlocker();
    }

    /**
     * Advances the block target block until the current block is a wall.
     *
     * @return true if a wall is found
     */
    private boolean advanceToWall(TargetBlock hitBlox) {
        Location curBlock;
        while ((curBlock = hitBlox.getCurrentBlock()) != null) {
            if (!canPassThroughBlock(curBlock)) {
                return true;
            }

            hitBlox.getNextBlock();
        }

        return false;
    }

    /**
     * Advances the block target block until the current block is a free spot.
     *
     * @return true if a free spot is found
     */
    private boolean advanceToFree(TargetBlock hitBlox) {
        Location curBlock;
        while ((curBlock = hitBlox.getCurrentBlock()) != null) {
            if (canPassThroughBlock(curBlock)) {
                return true;
            }

            hitBlox.getNextBlock();
        }

        return false;
    }

    @Override
    public boolean passThroughForwardWall(int range) {
        TargetBlock hitBlox = new TargetBlock(this, range, 0.2);

        if (!advanceToWall(hitBlox)) {
            return false;
        }

        if (!advanceToFree(hitBlox)) {
            return false;
        }

        Location foundBlock = hitBlox.getCurrentBlock();
        if (foundBlock != null) {
            setOnGround(foundBlock);
            return true;
        }

        return false;
    }

    @Override
    public boolean trySetPosition(Vector3 pos) {
        final Location location = getLocation();
        return trySetPosition(pos, location.getPitch(), location.getYaw());
    }

    @Override
    public File openFileOpenDialog(String[] extensions) {
        print(Caption.of("worldedit.platform.no-file-dialog"));
        return null;
    }

    @Override
    public File openFileSaveDialog(String[] extensions) {
        print(Caption.of("worldedit.platform.no-file-dialog"));
        return null;
    }

    //FAWE start

    /**
     * Run a task either async, or on the current thread.
     *
     * @param ifFree    the task to run if free
     * @param checkFree Whether to first check if a task is running
     * @param async     TODO description
     * @return false if the task was ran or queued
     */
    @Override
    public boolean runAction(Runnable ifFree, boolean checkFree, boolean async) {
        if (checkFree) {
            if (runningCount.get() != 0) {
                return false;
            }
        }
        Runnable wrapped = () -> {
            try {
                runningCount.addAndGet(1);
                ifFree.run();
            } finally {
                runningCount.decrementAndGet();
            }
        };
        if (async) {
            asyncNotifyQueue.run(wrapped);
        } else {
            TaskManager.taskManager().taskNow(wrapped, false);
        }
        return true;
    }
    //FAWE end

    @Override
    public boolean canDestroyBedrock() {
        return hasPermission("worldedit.override.bedrock");
    }

    @Override
    public void dispatchCUIEvent(CUIEvent event) {
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Player other2)) {
            return false;
        }
        return other2.getName().equals(getName());
    }

    @Override
    public int hashCode() {
        return getName().hashCode();
    }

    @Override
    public void checkPermission(String permission) throws AuthorizationException {
        if (!hasPermission(permission)) {
            throw new AuthorizationException(Caption.of("fawe.error.no-perm", permission));
        }
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public GameMode getGameMode() {
        return GameModes.SURVIVAL;
    }

    @Override
    public void setGameMode(GameMode gameMode) {

    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("Not supported");
    }

    @Override
    public boolean remove() {
        return false;
    }

    @Override
    public <B extends BlockStateHolder<B>> void sendFakeBlock(BlockVector3 pos, B block) {

    }

}
