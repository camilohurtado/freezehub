/**
 * The Industry design system frames cards, figures and primary buttons as blueprint
 * objects: square, hairline-bordered, with a "+" registration mark at each corner. The
 * marks are four empty elements, so wrapping them here keeps every call site to one tag
 * and makes it impossible to ship a frame with a mark missing.
 *
 * The classes come from the global `industry.css` layer, not a CSS Module, because they
 * are the design system's own names and are diffed against it.
 */
import type { ElementType, ReactNode } from 'react'

export function Blueprint({
  as: Tag = 'div',
  className,
  children,
  ...rest
}: {
  as?: ElementType
  className?: string
  children?: ReactNode
} & Record<string, unknown>) {
  return (
    <Tag className={className ? `blueprint ${className}` : 'blueprint'} {...rest}>
      <i className="corner tl" aria-hidden="true" />
      <i className="corner tr" aria-hidden="true" />
      <i className="corner bl" aria-hidden="true" />
      <i className="corner br" aria-hidden="true" />
      {children}
    </Tag>
  )
}
