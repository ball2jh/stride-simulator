package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.RefusalCause;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.Objects;

/**
 * Which side of the connection a tick workspace computes for: the client, which predicts, or the server, which
 * is authoritative and alone deals damage and changes the world.
 *
 * <p>The tick phases are shared by both copies of the player and ask the authority which branches are live. A
 * server authority owns the transaction's {@link ServerDamage} and binds it with the server copy through {@link #begin}
 * before any phase runs; a client authority has no server state and no damage effects. The server's synced shift
 * flag is read from the bound copy, so it cannot drift from a second copy in scratch. Not thread-safe.
 */
public final class TickAuthority {
	private static final TickAuthority CLIENT = new TickAuthority(null);

	private final ServerDamage damage;

	private ServerPlayerState server;

	/** The world writes of the branch that owns the server's world, or {@code null} until one is bound. */
	WorldChanges worldChanges;

	private TickAuthority(final ServerDamage damage) {
		this.damage = damage;
	}

	/** The one client authority: no server state, no damage. */
	public static TickAuthority client() {
		return CLIENT;
	}

	/** A fresh server authority with its own {@link ServerDamage}, to be bound with {@link #begin}. */
	public static TickAuthority server() {
		return new TickAuthority(new ServerDamage());
	}

	/** Whether this is a server authority. */
	public boolean isServer() {
		return this.damage != null;
	}

	/**
	 * Bind the server copy for one transaction and forget the last transaction's hits.
	 *
	 * @throws IllegalStateException on a client authority
	 */
	public void begin(final ServerPlayerState server) {
		if (!isServer()) {
			throw new IllegalStateException("client authority cannot begin a server transaction");
		}
		this.server = Objects.requireNonNull(server, "server");
		this.damage.begin(server);
	}

	/**
	 * The bound server copy.
	 *
	 * @throws IllegalStateException when no transaction is bound
	 */
	public ServerPlayerState serverState() {
		if (this.server == null) {
			throw new IllegalStateException("no server transaction is bound");
		}
		return this.server;
	}

	/**
	 * The bound transaction's damage sink.
	 *
	 * @throws IllegalStateException when no transaction is bound
	 */
	public ServerDamage damage() {
		if (this.server == null) {
			throw new IllegalStateException("no server transaction is bound");
		}
		return this.damage;
	}

	/**
	 * Refuse a client tick on a server workspace.
	 *
	 * @throws IllegalStateException on a server authority
	 */
	public void requireClient() {
		if (isServer()) {
			throw new IllegalStateException("client tick requires a client workspace");
		}
	}

	/**
	 * Refuse a server tick on any copy but the bound one.
	 *
	 * @throws IllegalStateException when no transaction is bound or {@code state} is not the bound copy
	 */
	public void requireServer(final ServerPlayerState state) {
		if (serverState() != state) {
			throw new IllegalStateException("server tick must use the bound transaction's player");
		}
	}

	/**
	 * Replace the cauldron at the cell with its lowered successor, the one admitted world write: a burning player
	 * is extinguished by a water or powder-snow cauldron, which loses a layer.
	 *
	 * @throws UnimplementedMechanicException when no branch owns the world, so the write has nowhere to go
	 */
	public void lowerCauldron(final SnapshotView world, final int x, final int y, final int z, final int successor) {
		if (this.worldChanges == null) {
			throw new UnimplementedMechanicException(RefusalCause.UNMODELED_WORLD_WRITE,
			    "cauldron mutation needs world ownership and interaction permission");
		}
		this.worldChanges.lowerCauldron(world, x, y, z, successor);
	}

	/** The world writes a branch that owns the server's world accepts; bound by {@code ServerTick}. */
	interface WorldChanges {
		/** Replace the cauldron at the cell with the palette entry {@code successor}. */
		void lowerCauldron(SnapshotView world, int x, int y, int z, int successor);
	}
}
