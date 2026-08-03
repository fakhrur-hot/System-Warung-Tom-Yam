import { useEffect, useRef } from 'react'
import { ADSTERRA_NATIVE_CONTAINER, ADSTERRA_NATIVE_SRC } from '../lib/adsterra'

/**
 * One Adsterra **Native Banner** unit.
 *
 * Unlike the plain banner, this format's `invoke.js` is async and fills a container `<div>` by id
 * instead of calling `document.write`, so it can be injected straight into the page — no iframe
 * needed, and it inherits the page's width like the in-feed AdSense unit it replaces.
 *
 * The container id must match the one Adsterra generated for the zone: their script looks the
 * element up by that exact id, so a mismatched value silently fills nothing.
 *
 * Mounted once per insertion point. The script tag is appended to this component's own subtree and
 * removed on unmount, so navigating between menu categories does not stack duplicate loaders.
 */
/**
 * Module-level claim on the container id.
 *
 * The id comes from Adsterra's snippet and their loader looks up that ONE element, so only a
 * single native unit can exist per page — DOM ids must be unique. `MenuView` renders an ad slot
 * every few items, which without this guard emitted several divs sharing one id: invalid HTML, and
 * Adsterra fills at most one of them while the rest sit empty. The first instance to mount wins;
 * later ones render nothing rather than duplicating it.
 */
let containerClaimed = false

export default function AdsterraNative() {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const injected = useRef(false)
  // Decided once at first render so the claim cannot flip mid-life and unmount a live ad.
  const isOwner = useRef<boolean | null>(null)
  if (isOwner.current === null) {
    isOwner.current = !containerClaimed
    if (isOwner.current) containerClaimed = true
  }

  useEffect(() => {
    if (!isOwner.current) return
    return () => {
      // Release on unmount so a later mount (e.g. navigating back to the menu) can claim it again.
      containerClaimed = false
    }
  }, [])

  useEffect(() => {
    if (!isOwner.current) return
    // Guards React StrictMode's dev double-invoke, which would otherwise append two scripts.
    if (injected.current || !ADSTERRA_NATIVE_SRC || !ADSTERRA_NATIVE_CONTAINER) return
    const host = hostRef.current
    if (!host) return
    injected.current = true

    const script = document.createElement('script')
    script.async = true
    script.setAttribute('data-cfasync', 'false')
    script.src = ADSTERRA_NATIVE_SRC
    script.onerror = () => {
      // Non-fatal: an ad blocker or dead zone just means no ad this session.
    }
    host.appendChild(script)

    return () => {
      // The container div itself is React-owned and unmounts with the component; only the script
      // element needs removing so a remount re-runs the loader cleanly.
      script.remove()
      injected.current = false
    }
  }, [])

  if (!ADSTERRA_NATIVE_SRC || !ADSTERRA_NATIVE_CONTAINER) return null
  // A second slot on the same page renders nothing — see containerClaimed above.
  if (!isOwner.current) return null

  return (
    <div ref={hostRef} className="py-2" aria-hidden="true">
      <div id={ADSTERRA_NATIVE_CONTAINER} />
    </div>
  )
}
