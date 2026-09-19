package com.nettarion.stride.simulator.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nettarion.stride.simulator.DamageEvent;
import com.nettarion.stride.simulator.HurtCause;
import com.nettarion.stride.simulator.PendingServerWriteException;
import com.nettarion.stride.simulator.PlayerState;
import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.server.TickAuthority;
import com.nettarion.stride.simulator.tick.Scratch;
import com.nettarion.stride.simulator.world.BlockEntry;
import com.nettarion.stride.simulator.world.FlatFloorView;
import com.nettarion.stride.simulator.world.WorldView;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The sentinel behaviors refuse on contact, on the server's copy only, and only for the hook family the capture
 * marked unmodeled or left unrecorded; farmland refuses a trample by how certain it is.
 */
final class BlockBehaviorRefusalTest {
	private static final WorldView WORLD = new FlatFloorView(-1, -64, 0.6F, 1.0F, 1.0F);

	@Test
	void anUnmodeledContactRefusesTheServersVisitAndStepButNotTheClients() {
		BlockBehavior behavior = of(WorldView.Contact.UNMODELED, WorldView.Landing.UNMODELED);
		assertTrue(behavior.hasContact());

		PlayerState client = new PlayerState();
		behavior.entityInside(client, 0, 0, 0, true, WORLD, new Scratch());
		behavior.stepOn(client, 0, -1, 0, new Scratch());

		Scratch server = server(new ServerPlayerState());
		assertEquals(RefusalCause.UNMODELED_BLOCK,
		    assertThrows(UnimplementedMechanicException.class,
		        () -> behavior.entityInside(server.authority.serverState(), 0, 0, 0, true, WORLD, server))
		        .cause());
		assertEquals(RefusalCause.UNMODELED_BLOCK,
		    assertThrows(UnimplementedMechanicException.class,
		        () -> behavior.stepOn(server.authority.serverState(), 0, -1, 0, server))
		        .cause());
	}

	@Test
	void anUnmodeledLandingRefusesInFallOn() {
		BlockBehavior behavior = of(WorldView.Contact.UNMODELED, WorldView.Landing.UNMODELED);
		Scratch server = server(new ServerPlayerState());
		UnimplementedMechanicException refusal = assertThrows(
		    UnimplementedMechanicException.class, () -> behavior.fallOn(5.0, 0, -1, 0, server.authority.damage()));
		assertEquals(RefusalCause.UNMODELED_BLOCK, refusal.cause());
		assertTrue(server.authority.damage().dealt().isEmpty(), "the refusal precedes any hit");
	}

	@Test
	void anUnrecordedLandingRefusesInFallOnAndHasNoStepOn() {
		BlockBehavior behavior = of(WorldView.Contact.UNRECORDED, WorldView.Landing.UNRECORDED);
		Scratch server = server(new ServerPlayerState());
		// None of the blocks whose hook depends on unrecorded state has a stepOn.
		behavior.stepOn(server.authority.serverState(), 0, -1, 0, server);
		assertEquals(RefusalCause.UNDECLARED_BLOCK_STATE,
		    assertThrows(
		        UnimplementedMechanicException.class, () -> behavior.fallOn(5.0, 0, -1, 0, server.authority.damage()))
		        .cause());
		assertEquals(RefusalCause.UNDECLARED_BLOCK_STATE,
		    assertThrows(UnimplementedMechanicException.class,
		        () -> behavior.entityInside(server.authority.serverState(), 0, 0, 0, true, WORLD, server))
		        .cause());
	}

	@Test
	void anUnmodeledContactBesideAnOrdinaryLandingLandsOrdinarily() {
		// The capture said what the landing does; only the contact is missing,
		// so the sentinel must not name fallOn as the hook it lacks.
		BlockBehavior behavior = of(WorldView.Contact.UNMODELED, WorldView.Landing.ORDINARY);
		assertTrue(behavior.hasContact());
		Scratch server = server(new ServerPlayerState());
		behavior.fallOn(5.0, 0, -1, 0, server.authority.damage());
		assertEquals(
		    List.of(HurtCause.FALL), server.authority.damage().dealt().stream().map(DamageEvent::cause).toList());
		assertEquals(18.0F, server.authority.serverState().health, "five blocks at multiplier one is two points");
		assertEquals(RefusalCause.UNMODELED_BLOCK,
		    assertThrows(UnimplementedMechanicException.class,
		        () -> behavior.entityInside(server.authority.serverState(), 0, 0, 0, true, WORLD, server))
		        .cause());
	}

	@Test
	void anUnmodeledLandingBesideNoContactIsInvisibleToTheTraversal() {
		BlockBehavior behavior = of(WorldView.Contact.NONE, WorldView.Landing.UNMODELED);
		assertFalse(behavior.hasContact(), "no contact fact, so no visit");
		assertFalse(behavior.hasEntityInside());
		Scratch server = server(new ServerPlayerState());
		behavior.stepOn(server.authority.serverState(), 0, -1, 0, server);
		behavior.entityInside(server.authority.serverState(), 0, 0, 0, true, WORLD, server);
		assertEquals(RefusalCause.UNMODELED_BLOCK,
		    assertThrows(
		        UnimplementedMechanicException.class, () -> behavior.fallOn(5.0, 0, -1, 0, server.authority.damage()))
		        .cause());
	}

	@Test
	void anUnrecordedContactBesideAnOrdinaryLandingLandsOrdinarily() {
		BlockBehavior behavior = of(WorldView.Contact.UNRECORDED, WorldView.Landing.ORDINARY);
		Scratch server = server(new ServerPlayerState());
		behavior.fallOn(5.0, 0, -1, 0, server.authority.damage());
		assertEquals(18.0F, server.authority.serverState().health);
	}

	@Test
	void sentinelsAreSharedPerRefusedFamilySet() {
		assertSame(of(WorldView.Contact.UNMODELED, WorldView.Landing.ORDINARY),
		    of(WorldView.Contact.UNMODELED, WorldView.Landing.ORDINARY));
		assertSame(of(WorldView.Contact.UNMODELED, WorldView.Landing.UNMODELED),
		    of(WorldView.Contact.UNMODELED, WorldView.Landing.UNMODELED));
		assertEquals("unmodeled contact", of(WorldView.Contact.UNMODELED, WorldView.Landing.ORDINARY).toString());
		assertEquals("unrecorded landing state", of(WorldView.Contact.NONE, WorldView.Landing.UNRECORDED).toString());
	}

	@Test
	void anUnmodeledHookBesideAnUnrecordedOneRefusesAtCompileTime() {
		// Two sentinels of different causes on one entry: refused as inconsistent
		// facts rather than guessed, as before the sentinels carried families.
		UnimplementedMechanicException refusal = assertThrows(
		    UnimplementedMechanicException.class, () -> of(WorldView.Contact.UNMODELED, WorldView.Landing.UNRECORDED));
		assertEquals(RefusalCause.INADMISSIBLE_BLOCK_FACTS, refusal.cause());
	}

	@Test
	void aCertainFarmlandTrampleRefusesAsAWorldWrite() {
		// nextFloat() < 1.5 - 0.5 always holds: vanilla turns the farmland to dirt,
		// a block update the simulator does not write, so not a random outcome.
		Scratch server = server(new ServerPlayerState());
		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> FarmlandBlock.INSTANCE.fallOn(1.5, 0, -1, 0, server.authority.damage()));
		assertEquals(RefusalCause.UNMODELED_WORLD_WRITE, refusal.cause());
	}

	@Test
	void anUncertainFarmlandTrampleRefusesAsServerRandom() {
		// nextFloat() < 1.0 - 0.5 is a coin the server flips.
		Scratch server = server(new ServerPlayerState());
		PendingServerWriteException refusal = assertThrows(PendingServerWriteException.class,
		    () -> FarmlandBlock.INSTANCE.fallOn(1.0, 0, -1, 0, server.authority.damage()));
		assertEquals(RefusalCause.PENDING_SERVER_RANDOM, refusal.cause());
	}

	@Test
	void aShortFallOnFarmlandLandsOrdinarily() {
		// nextFloat() < 0.5 - 0.5 never holds, and half a block is no hit.
		Scratch server = server(new ServerPlayerState());
		FarmlandBlock.INSTANCE.fallOn(0.5, 0, -1, 0, server.authority.damage());
		assertTrue(server.authority.damage().dealt().isEmpty());
		assertEquals(20.0F, server.authority.serverState().health);
	}

	private static BlockBehavior of(final WorldView.Contact contact, final WorldView.Landing landing) {
		return BlockBehavior.of(BlockEntry.builder(1, "test:sentinel").contact(contact).landing(landing).build());
	}

	private static Scratch server(final ServerPlayerState state) {
		Scratch scratch = new Scratch(TickAuthority.server());
		scratch.authority.begin(state);
		return scratch;
	}
}
