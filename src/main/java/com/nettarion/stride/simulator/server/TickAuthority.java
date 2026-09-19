package com.nettarion.stride.simulator.server;

import com.nettarion.stride.simulator.Refusal;
import com.nettarion.stride.simulator.ServerPlayerState;
import com.nettarion.stride.simulator.UnimplementedMechanicException;
import com.nettarion.stride.simulator.world.SnapshotView;
import java.util.Objects;

/**
 * The fixed client or server authority of a stepping workspace.
 *
 * <p>A server authority owns the transaction's {@link Survival} and binds it
 * together with the server player before any phase runs. Client authority has
 * no server state or survival effects. The server's synced shift flag is read
 * from that bound player; it cannot drift from a second copy in scratch.
 */
public final class TickAuthority {
	private static final TickAuthority CLIENT = new TickAuthority(null);

	private final Survival survival;
	private ServerPlayerState server;
	interface WorldChanges {
		void lowerCauldron(SnapshotView world, int x, int y, int z, int successor);
	}
	WorldChanges worldChanges;
	public void lowerCauldron(final SnapshotView world, final int x, final int y, final int z, final int successor) {
		if (this.worldChanges == null)
			throw new UnimplementedMechanicException(
			    Refusal.UNMODELLED_WORLD_WRITE, "cauldron mutation needs world ownership and interaction permission");
		this.worldChanges.lowerCauldron(world, x, y, z, successor);
	}

	private TickAuthority(final Survival survival) {
		this.survival = survival;
	}

	public static TickAuthority client() {
		return CLIENT;
	}

	public static TickAuthority server() {
		return new TickAuthority(new Survival());
	}

	public boolean isServer() {
		return this.survival != null;
	}

	/** Bind both the movement authority and survival effects for one transaction. */
	public void begin(final ServerPlayerState server) {
		if (!isServer()) {
			throw new IllegalStateException("client authority cannot begin a server transaction");
		}
		this.server = Objects.requireNonNull(server, "server");
		this.survival.begin(server);
	}

	public ServerPlayerState serverState() {
		if (this.server == null) {
			throw new IllegalStateException("no server transaction is bound");
		}
		return this.server;
	}

	public Survival survival() {
		if (this.server == null) {
			throw new IllegalStateException("no server transaction is bound");
		}
		return this.survival;
	}

	public void requireClient() {
		if (isServer()) {
			throw new IllegalArgumentException("client tick requires a client workspace");
		}
	}

	public void requireServer(final ServerPlayerState state) {
		if (serverState() != state) {
			throw new IllegalArgumentException("server tick must use the bound transaction's player");
		}
	}
}
