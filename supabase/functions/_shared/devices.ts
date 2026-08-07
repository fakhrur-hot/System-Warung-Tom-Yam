/**
 * Shared device-list housekeeping.
 *
 * Lives here rather than inside one function because two separate paths need it — revoking a device
 * and the owner signing back in — and a café's device list is exactly the kind of thing that ends up
 * with two slightly different tidy-up rules if each caller writes its own.
 */
import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2";

/**
 * How many revoked devices to keep.
 *
 * Enough to show a café the recent history of devices it has retired — a replaced phone, a staff
 * tablet handed back — without letting dead rows crowd out the live ones it has to manage.
 */
export const MAX_REVOKED_DEVICES = 5;

/**
 * Delete every revoked device beyond the newest [MAX_REVOKED_DEVICES].
 *
 * ## Why this is needed at all
 *
 * REVOKE and REJECT are soft: both set `status = 'REVOKED'` and leave the row in place, and until
 * now nothing ever removed one. There is no DELETE action in the devices API and never has been, so
 * a café that has replaced a few phones accumulates a list made mostly of dead entries it has no
 * way to clear.
 *
 * ## Why deleting the row is safe
 *
 * Nothing in the schema references `devices` by foreign key, so there is nothing to orphan. A
 * revoked device's `api_key_hash` and `session_token_hash` were already made useless by the status
 * change that revoked it, so removing the row takes no live credential with it. `admin-recovery`
 * already deletes duplicate device rows on the same basis.
 *
 * ## Ordering
 *
 * Newest first, so the survivors are the most recent revocations — the ones an owner might still
 * want the trail of. The oldest are the ones nobody remembers.
 *
 * Never throws. Every caller is doing something more important than housekeeping (revoking a device,
 * signing an owner in) and none of them should fail because the tidy-up afterwards did.
 */
export async function pruneRevokedDevices(supabase: SupabaseClient): Promise<number> {
  try {
    const { data: revoked } = await supabase
      .from("devices")
      .select("id")
      .eq("status", "REVOKED")
      .order("created_at", { ascending: false });

    const surplus = (revoked ?? []).slice(MAX_REVOKED_DEVICES).map((d: { id: string }) => d.id);
    if (surplus.length === 0) return 0;

    await supabase.from("devices").delete().in("id", surplus);
    return surplus.length;
  } catch (_e) {
    return 0;
  }
}
