package me.jddev0.iws;

import com.hypixel.hytale.builtin.blocktick.system.ChunkBlockTickSystem;
import com.hypixel.hytale.builtin.fluid.FluidSystems;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector2i;
import com.hypixel.hytale.server.core.asset.type.blocktick.BlockTickStrategy;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.fluid.Fluid;
import com.hypixel.hytale.server.core.asset.type.fluid.FluidTicker;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;

import javax.annotation.Nonnull;
import java.util.Set;

public class InfiniteWaterSystem extends EntityTickingSystem<ChunkStore> {
    @Nonnull
    private static final Query<ChunkStore> QUERY = Query.and(FluidSection.getComponentType(), ChunkSection.getComponentType());
    @Nonnull
    private static final Set<Dependency<ChunkStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.AFTER, FluidSystems.Ticking.class), new SystemDependency<>(Order.BEFORE, ChunkBlockTickSystem.Ticking.class)
    );

    @Override
    public void tick(
            float dt,
            int index,
            @Nonnull ArchetypeChunk<ChunkStore> archetypeChunk,
            @Nonnull Store<ChunkStore> store,
            @Nonnull CommandBuffer<ChunkStore> commandBuffer
    ) {
        ChunkSection chunkSectionComponent = archetypeChunk.getComponent(index, ChunkSection.getComponentType());

        assert chunkSectionComponent != null;

        FluidSection fluidSectionComponent = archetypeChunk.getComponent(index, FluidSection.getComponentType());

        assert fluidSectionComponent != null;

        Ref<ChunkStore> chunkRef = chunkSectionComponent.getChunkColumnReference();
        BlockChunk blockChunkComponent = commandBuffer.getComponent(chunkRef, BlockChunk.getComponentType());

        assert blockChunkComponent != null;

        BlockSection blockSection = blockChunkComponent.getSectionAtIndex(fluidSectionComponent.getY());
        if (blockSection != null) {
            if (blockSection.getTickingBlocksCountCopy() != 0) {
                FluidTicker.CachedAccessor accessor = FluidTicker.CachedAccessor.of(commandBuffer, fluidSectionComponent, blockSection, 5);
                blockSection.forEachTicking(accessor, commandBuffer, fluidSectionComponent.getY(), (accessor1, commandBuffer1, x, y, z, block) -> {
                    FluidSection fluidSection = accessor1.selfFluidSection;
                    BlockSection blockSectionInner = accessor1.selfBlockSection;
                    int fluidId = fluidSection.getFluidId(x, y, z);
                    if (fluidId == 0) {
                        return BlockTickStrategy.IGNORED;
                    } else {
                        Fluid fluid = Fluid.getAssetMap().getAsset(fluidId);
                        if(fluid != null && fluid.getId().equals("Water_Source")) {
                            int worldX = fluidSection.getX() << 5 | x;
                            int worldZ = fluidSection.getZ() << 5 | z;

                            for (Vector2i offset:new Vector2i[]{new Vector2i(-1, 0), new Vector2i(1, 0), new Vector2i(0, -1), new Vector2i(0, 1)}) {
                                int blockX = offset.x + worldX;
                                int blockZ = offset.y + worldZ;
                                boolean isDifferentSection = !ChunkUtil.isSameChunkSection(worldX, y, worldZ, blockX, y, blockZ);
                                FluidSection targetFluidSection = isDifferentSection?accessor.getFluidSectionByBlock(blockX, y, blockZ):fluidSection;
                                BlockSection targetBlockSection = isDifferentSection?accessor.getBlockSectionByBlock(blockX, y, blockZ):blockSectionInner;
                                if(targetBlockSection == null) {
                                    return BlockTickStrategy.WAIT_FOR_ADJACENT_CHUNK_LOAD;
                                }

                                int fluidIdTarget = targetFluidSection.getFluidId(blockX, y, blockZ);
                                Fluid fluidTarget = Fluid.getAssetMap().getAsset(fluidIdTarget);
                                if(fluidTarget == null || fluidTarget.getId().equals("Empty") || fluidTarget.getId().equals("Water")) {
                                    int waterSourceCount = 0;

                                    for (Vector2i offsetInner:new Vector2i[]{new Vector2i(-1, 0), new Vector2i(1, 0), new Vector2i(0, -1), new Vector2i(0, 1)}) {
                                        int checkBlockX = offsetInner.x + blockX;
                                        int checkBlockZ = offsetInner.y + blockZ;
                                        boolean isDifferentSectionInner = !ChunkUtil.isSameChunkSection(worldX, y, worldZ, checkBlockX, y, checkBlockZ);
                                        FluidSection checkFluidSection = isDifferentSectionInner?accessor.getFluidSectionByBlock(checkBlockX, y, checkBlockZ):fluidSection;
                                        BlockSection checkBlockSection = isDifferentSectionInner?accessor.getBlockSectionByBlock(checkBlockX, y, checkBlockZ):blockSectionInner;
                                        if(checkFluidSection == null) {
                                            return BlockTickStrategy.WAIT_FOR_ADJACENT_CHUNK_LOAD;
                                        }

                                        int fluidIdCheck = checkFluidSection.getFluidId(checkBlockX, y, checkBlockZ);
                                        Fluid fluidCheck = Fluid.getAssetMap().getAsset(fluidIdCheck);
                                        if(fluidCheck != null && fluidCheck.getId().equals("Water_Source")) {
                                            waterSourceCount++;
                                        }
                                    }

                                    if(waterSourceCount >= 2) {
                                        int blockId = targetBlockSection.get(blockX, y, blockZ);
                                        if(FluidTicker.isFullySolid(BlockType.getAssetMap().getAsset(blockId))) {
                                            fluidSection.setFluid(blockX, y, blockZ, 0, (byte)0);
                                        }else {
                                            targetFluidSection.setFluid(blockX, y, blockZ, fluidId, (byte)fluid.getMaxFluidLevel());
                                            targetBlockSection.setTicking(blockX, y, blockZ, true);
                                            FluidTicker.setTickingSurrounding(accessor1, targetBlockSection, blockX, y, blockZ);
                                        }
                                    }
                                }
                            }

                            return BlockTickStrategy.SLEEP;
                        }

                        return BlockTickStrategy.IGNORED;
                    }
                });
            }
        }
    }

    @Nonnull
    @Override
    public Query<ChunkStore> getQuery() {
        return QUERY;
    }

    @Nonnull
    @Override
    public Set<Dependency<ChunkStore>> getDependencies() {
        return DEPENDENCIES;
    }
}
