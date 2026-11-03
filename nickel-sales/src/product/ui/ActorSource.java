package product.ui;

import auth.contract.User;

/**
 * Gives a dialog the person who is logged in RIGHT NOW. It is read again for
 * every action, so a role taken away (or an expired session) is noticed on the
 * next click instead of at the next login. Null means the session has ended.
 */
@FunctionalInterface
interface ActorSource {

    User current();
}
