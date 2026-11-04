package system.contract;

import system.model.StatusItem;

/**
 * How a component reports its current health (IStatusProvider in the class diagram), so the
 * System Status tab never reads another module's internals. Should not throw; if it does, the
 * tab shows that component as a problem.
 */
@FunctionalInterface
public interface StatusProvider {

    StatusItem status();
}
