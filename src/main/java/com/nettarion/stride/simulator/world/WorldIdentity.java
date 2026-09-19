package com.nettarion.stride.simulator.world;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The process-wide source of {@link WorldView#identity()} numbers.
 *
 * <p>Every view that wants retained proofs keyed to it takes one number here at
 * construction and returns it for its lifetime. Numbers start above zero, since
 * zero is the identity of a view that declines to be proven against, and never
 * repeat within a process, so a proof keyed to a view that no longer exists is a
 * miss rather than a hit against whichever view took its place.
 */
public final class WorldIdentity {
	private static final AtomicLong NEXT = new AtomicLong(1L);

	private WorldIdentity() {}

	/** A fresh identity no other view in this process has or will have. */
	public static long next() {
		return NEXT.getAndIncrement();
	}
}
