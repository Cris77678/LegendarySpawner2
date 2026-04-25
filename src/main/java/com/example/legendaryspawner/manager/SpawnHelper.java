package com.example.legendaryspawner.manager;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.example.legendaryspawner.LegendarySpawnerMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;

import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnHelper {

    private static final Random RNG = new Random();

    // ✅ REEMPLAZO DE PersistentData
    public static final ConcurrentHashMap<UUID, String> SPAWN_TAGS = new ConcurrentHashMap<>();

    public static Optional<PokemonEntity> spawnOnServerThread(
            ServerWorld world, ServerPlayerEntity player, String properties, int radius) {

        Optional<BlockPos> posOpt = findSpawnPos(world, player.getBlockPos(), radius);
        if (posOpt.isEmpty()) {
            LegendarySpawnerMod.LOGGER.warn(
                    "[LegendarySpawner] No se encontró posición segura para spawn de '{}'", properties);
            return Optional.empty();
        }

        BlockPos pos = posOpt.get();

        try {
            PokemonEntity entity = PokemonProperties.Companion.parse(properties).createEntity(world);

            if (entity == null) {
                LegendarySpawnerMod.LOGGER.error(
                        "[LegendarySpawner] PokemonProperties retornó null para '{}'", properties);
                return Optional.empty();
            }

            // ✅ FIX: reemplazo de getPersistentData()
            UUID id = entity.getUuid();
            SPAWN_TAGS.put(id, properties);

            entity.setPosition(Vec3d.ofCenter(pos));
            world.spawnEntity(entity);

            LegendarySpawnerMod.LOGGER.info(
                    "[LegendarySpawner] Spawneado '{}' en [{}, {}, {}]",
                    properties, pos.getX(), pos.getY(), pos.getZ());

            return Optional.of(entity);

        } catch (Exception e) {
            LegendarySpawnerMod.LOGGER.error(
                    "[LegendarySpawner] Error creando entidad para '{}'", properties, e);
            return Optional.empty();
        }
    }

    public static Optional<BlockPos> findSpawnPos(ServerWorld world, BlockPos center, int radius) {
        boolean isNether = world.getRegistryKey().equals(World.NETHER);

        for (int attempt = 0; attempt < 30; attempt++) {
            int dx = RNG.nextInt(radius * 2) - radius;
            int dz = RNG.nextInt(radius * 2) - radius;

            int x = center.getX() + dx;
            int z = center.getZ() + dz;

            int y = isNether
                    ? findSafeNetherY(world, x, z)
                    : world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);

            if (y == Integer.MIN_VALUE) continue;

            BlockPos pos = new BlockPos(x, y, z);

            if (isSafe(world, pos)) {
                return Optional.of(pos);
            }
        }

        return Optional.empty();
    }

    public static int findSafeNetherY(ServerWorld world, int x, int z) {
        for (int y = 32; y < 100; y++) {
            BlockPos floor = new BlockPos(x, y - 1, z);
            BlockPos foot  = new BlockPos(x, y, z);
            BlockPos head  = new BlockPos(x, y + 1, z);

            if (world.getBlockState(floor).isSolidBlock(world, floor)
                    && world.getBlockState(foot).isAir()
                    && world.getBlockState(head).isAir()) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    public static boolean isSafe(ServerWorld world, BlockPos pos) {
        BlockPos below = pos.down();

        BlockState floor = world.getBlockState(below);
        BlockState foot  = world.getBlockState(pos);
        BlockState head  = world.getBlockState(pos.up());

        if (!floor.isSolidBlock(world, below)) return false;
        if (!foot.isAir() || !head.isAir()) return false;

        if (floor.isOf(Blocks.LAVA) || foot.isOf(Blocks.LAVA) || floor.isOf(Blocks.FIRE)) return false;

        return true;
    }
}