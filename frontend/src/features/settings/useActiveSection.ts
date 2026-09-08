import { useEffect, useState } from 'react'

/**
 * Which section the reader is currently looking at, for the rail to mark (`1h`, FZ-116).
 *
 * An observer rather than a scroll handler: the browser does the geometry, and it does
 * not fire on every pixel. The top band is deliberately narrow — a section counts as
 * "current" once its heading is near the top of the viewport, which is where a reader
 * looks, rather than when any part of it is visible. Without that, a long section and the
 * short one after it are both on screen and the rail flickers between them.
 */
export function useActiveSection(ids: string[]): string | null {
  const [active, setActive] = useState<string | null>(ids[0] ?? null)

  useEffect(() => {
    // Without the observer the rail is still a working set of anchors — it simply stops
     // following the scroll. Degrading is right for an enhancement; throwing would take
     // the whole page down with it, which is what jsdom found.
    if (typeof IntersectionObserver === 'undefined') return

    const elements = ids
      .map((id) => document.getElementById(id))
      .filter((element): element is HTMLElement => element !== null)
    if (elements.length === 0) return

    const observer = new IntersectionObserver(
      (entries) => {
        const entered = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0]
        if (entered) setActive(entered.target.id)
      },
      // A band across the top of the viewport, not the whole of it.
      { rootMargin: '-64px 0px -70% 0px', threshold: 0 },
    )

    elements.forEach((element) => observer.observe(element))
    return () => observer.disconnect()
  }, [ids])

  return active
}
