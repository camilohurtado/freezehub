import { useEffect, useRef, useState } from 'react'
import styles from './CreateRestrictionPage.module.css'

/** How many unselected options stay on the page before the rest go behind "n more…". */
const VISIBLE = 4

export interface ScopeOption {
  id: number
  name: string
}

/**
 * One scope dimension as chips (`1e`, FZ-108).
 *
 * Replaces a native `<select multiple>`, which `05-frontend.md` accepted knowingly — so
 * this reverses a recorded decision rather than ignoring one. The reason is that the
 * native control hides the thing that matters: what is selected. A multi-select shows a
 * scrolling list in which selection is a background colour, and with more than a handful
 * of applications the chosen ones scroll out of sight. Chips put the selection in the
 * page and the choice one click away.
 *
 * Selected chips come first and carry ×; the common unselected ones carry +. Everything
 * past {@link VISIBLE} goes behind "n more…", which opens the full list — the operator's
 * direction, and the reason a long catalog does not turn this into a wall of chips.
 */
export function ScopeChips({
  label,
  options,
  selected,
  onChange,
  loading,
}: {
  label: string
  options: ScopeOption[]
  selected: number[]
  onChange: (ids: number[]) => void
  loading: boolean
}) {
  const [dialogOpen, setDialogOpen] = useState(false)

  const chosen = options.filter((option) => selected.includes(option.id))
  const rest = options.filter((option) => !selected.includes(option.id))
  const shown = rest.slice(0, VISIBLE)
  const hidden = rest.length - shown.length

  const toggle = (id: number) =>
    onChange(selected.includes(id) ? selected.filter((each) => each !== id) : [...selected, id])

  return (
    <div className={styles.dimension}>
      <div className={styles.dimensionLabel} id={`scope-${label.toLowerCase()}`}>
        {label}
      </div>

      {loading ? (
        <p className={styles.dimensionEmpty}>Loading…</p>
      ) : options.length === 0 ? (
        <p className={styles.dimensionEmpty}>None in the catalog.</p>
      ) : (
        <div className={styles.chips} role="group" aria-labelledby={`scope-${label.toLowerCase()}`}>
          {chosen.map((option) => (
            <button
              key={option.id}
              type="button"
              className={`tag tag-accent ${styles.chip}`}
              aria-pressed={true}
              onClick={() => toggle(option.id)}
            >
              {option.name} <span aria-hidden="true">×</span>
            </button>
          ))}
          {shown.map((option) => (
            <button
              key={option.id}
              type="button"
              className={`tag tag-outline ${styles.chip}`}
              aria-pressed={false}
              onClick={() => toggle(option.id)}
            >
              {option.name} <span aria-hidden="true">+</span>
            </button>
          ))}
          {hidden > 0 && (
            <button
              type="button"
              className={`tag tag-outline ${styles.chip}`}
              onClick={() => setDialogOpen(true)}
            >
              {hidden} more…
            </button>
          )}
        </div>
      )}

      {dialogOpen && (
        <ScopeDialog
          label={label}
          options={options}
          selected={selected}
          onToggle={toggle}
          onClose={() => setDialogOpen(false)}
        />
      )}
    </div>
  )
}

/**
 * Every value in the dimension, for picking from a catalog too long to show inline.
 *
 * A real modal rather than an expanding list: it is a deliberate detour from the form, and
 * the page behind it should not reflow while you scan. Escape closes it, focus moves into
 * it on open and the backdrop is clickable, because a dialog that traps you is worse than
 * the multi-select it replaced.
 */
function ScopeDialog({
  label,
  options,
  selected,
  onToggle,
  onClose,
}: {
  label: string
  options: ScopeOption[]
  selected: number[]
  onToggle: (id: number) => void
  onClose: () => void
}) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div
      className={`dialog-backdrop ${styles.backdrop}`}
      // The backdrop closes on click; the dialog itself stops the click travelling.
      onClick={onClose}
    >
      <div
        className={`dialog ${styles.dialog}`}
        role="dialog"
        aria-modal="true"
        aria-label={`Choose ${label.toLowerCase()}`}
        onClick={(event) => event.stopPropagation()}
      >
        <div className={styles.dialogHead}>
          <h2 className={styles.dialogTitle}>{label}</h2>
          <span className={styles.dialogCount}>
            {selected.length} of {options.length} selected
          </span>
        </div>

        <ul className={styles.dialogList}>
          {options.map((option) => {
            const isSelected = selected.includes(option.id)
            return (
              <li key={option.id}>
                <label className={styles.dialogOption}>
                  <input
                    type="checkbox"
                    checked={isSelected}
                    onChange={() => onToggle(option.id)}
                  />
                  <span>{option.name}</span>
                </label>
              </li>
            )
          })}
        </ul>

        <div className={styles.dialogActions}>
          <button ref={closeRef} className="btn btn-primary" type="button" onClick={onClose}>
            Done
          </button>
        </div>
      </div>
    </div>
  )
}
