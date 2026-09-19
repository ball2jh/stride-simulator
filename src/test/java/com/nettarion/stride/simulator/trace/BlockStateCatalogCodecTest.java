package com.nettarion.stride.simulator.trace;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.BlockStateCatalog;
import com.nettarion.stride.simulator.world.ShapeBox;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BlockStateCatalogCodecTest {
	@TempDir Path temporaryDirectory;

	private static BlockStateCatalog sample() {
		BlockEntry air = BlockEntry.builder(0, "minecraft:air").build();
		BlockEntry stone = BlockEntry.builder(1, "minecraft:stone").fullCube().build();
		BlockEntry waterCauldron =
		    BlockEntry.builder(7, "minecraft:water_cauldron").boxes(new ShapeBox(0, 0, 0, 1, 0.25, 1)).build();
		BlockEntry emptyCauldron =
		    BlockEntry.builder(8, "minecraft:cauldron").boxes(new ShapeBox(0, 0, 0, 1, 0.25, 1)).build();
		BlockStateCatalog.Cauldron transition = new BlockStateCatalog.Cauldron(7, 8,
		    List.of(new ShapeBox(0.125, 0.25, 0.125, 0.875, Double.longBitsToDouble(0x3FE0000000000001L), 0.875)));
		Map<Integer, BlockStateCatalog.InsideShape> inside =
		    Map.of(7, new BlockStateCatalog.InsideShape(List.of(new ShapeBox(0, 0, 0, 1, -0.0, 1)), false), 1,
		        new BlockStateCatalog.InsideShape(List.of(ShapeBox.FULL_CUBE), true));
		return new BlockStateCatalog(TraceFixtures.MINECRAFT_VERSION, List.of(air, stone, waterCauldron, emptyCauldron),
		    List.of(transition), inside);
	}

	@Test
	void roundTripPreservesEntriesCauldronsAndInsideShapes() throws IOException {
		BlockStateCatalog original = sample();
		Path path = this.temporaryDirectory.resolve("sample.catalog");

		BlockStateCatalogCodec.write(original, path);
		BlockStateCatalog decoded = BlockStateCatalogCodec.read(path, TraceFixtures.MINECRAFT_VERSION);

		assertEquals(original.minecraftVersion(), decoded.minecraftVersion());
		assertEquals(original.entries(), decoded.entries());
		assertEquals(original.cauldrons(), decoded.cauldrons());
		assertEquals(original.insideShapes(), decoded.insideShapes());
		assertArrayEquals(BlockStateCatalogCodec.bytes(original), BlockStateCatalogCodec.bytes(decoded),
		    "the canonical bytes are stable across a round trip");
	}

	@Test
	void theStreamOpensWithTheFixedMagicAndVersion() throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(BlockStateCatalogCodec.bytes(sample())));

		assertEquals(BlockStateCatalogCodec.MAGIC, in.readUTF());
		assertEquals(BlockStateCatalogCodec.FORMAT_VERSION, in.readInt());
		assertEquals(TraceFixtures.MINECRAFT_VERSION, in.readUTF());
	}

	@Test
	void refusesAnotherMinecraftVersionTrailingDataAndACorruptCount() throws IOException {
		byte[] encoded = BlockStateCatalogCodec.bytes(sample());

		assertThrows(IOException.class, () -> BlockStateCatalogCodec.read(encoded, "1.21"));
		byte[] trailing = new byte[encoded.length + 1];
		System.arraycopy(encoded, 0, trailing, 0, encoded.length);
		assertThrows(IOException.class, () -> BlockStateCatalogCodec.read(trailing, TraceFixtures.MINECRAFT_VERSION));
		byte[] truncated = new byte[encoded.length - 9];
		System.arraycopy(encoded, 0, truncated, 0, truncated.length);
		assertThrows(IOException.class, () -> BlockStateCatalogCodec.read(truncated, TraceFixtures.MINECRAFT_VERSION));
	}

	@Test
	void theBundledCatalogReadsAndSurvivesAReEncoding() throws IOException {
		try (InputStream fixture =
		         BlockStateCatalogCodecTest.class.getResourceAsStream("/captures/live-pose-echo-boundary.catalog")) {
			byte[] encoded = fixture.readAllBytes();

			BlockStateCatalog decoded = BlockStateCatalogCodec.read(encoded, TraceFixtures.MINECRAFT_VERSION);
			// The bundled stream embeds the pre-0.1 outside label ROLLOUT_TERMINATING, which the writer
			// spells REFUSING, so its bytes are not reproduced; its facts are.
			BlockStateCatalog reEncoded =
			    BlockStateCatalogCodec.read(BlockStateCatalogCodec.bytes(decoded), TraceFixtures.MINECRAFT_VERSION);

			assertEquals(5, decoded.entries().size());
			assertEquals(decoded.entries(), reEncoded.entries());
			assertEquals(decoded.cauldrons(), reEncoded.cauldrons());
			assertEquals(decoded.insideShapes(), reEncoded.insideShapes());
		}
	}
}
