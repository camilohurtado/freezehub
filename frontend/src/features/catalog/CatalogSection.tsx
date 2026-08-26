import { useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../api/client'
import styles from './CatalogPage.module.css'

export interface CatalogItem {
  id: number
  name: string
}

/**
 * One catalog type: list, create, rename, delete.
 *
 * Shared by teams, applications and environments so the three do not become three
 * near-identical implementations that drift. Applications pass extra per-row content
 * through {@code renderExtra} for their team assignment.
 */
export function CatalogSection<T extends CatalogItem>({
  heading,
  description,
  placeholder,
  items,
  isPending,
  isError,
  error,
  onCreate,
  onRename,
  onDelete,
  renderExtra,
}: {
  heading: string
  description: string
  placeholder: string
  items: T[] | undefined
  isPending: boolean
  isError: boolean
  error: Error | null
  onCreate: (name: string) => Promise<unknown>
  onRename: (id: number, name: string) => Promise<unknown>
  onDelete: (id: number) => Promise<unknown>
  renderExtra?: (item: T) => ReactNode
}) {
  const [newName, setNewName] = useState('')
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editingName, setEditingName] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  /**
   * Turns a rejected write into something readable. `409` is the one users will actually
   * hit — a duplicate name, or deleting something a restriction still references — so it
   * must say what happened rather than "request failed".
   */
  function report(caught: unknown) {
    if (caught instanceof ApiError) setActionError(caught.message)
    else setActionError('Something went wrong. Please try again.')
  }

  async function submitNew(event: FormEvent) {
    event.preventDefault()
    setActionError(null)
    try {
      await onCreate(newName.trim())
      setNewName('')
    } catch (caught) {
      report(caught)
    }
  }

  async function submitRename(event: FormEvent) {
    event.preventDefault()
    if (editingId === null) return
    setActionError(null)
    try {
      await onRename(editingId, editingName.trim())
      setEditingId(null)
    } catch (caught) {
      report(caught)
    }
  }

  async function remove(item: T) {
    setActionError(null)
    try {
      await onDelete(item.id)
    } catch (caught) {
      report(caught)
    }
  }

  return (
    <section className={styles.section} aria-labelledby={`catalog-${heading.toLowerCase()}`}>
      <h2 className={styles.sectionHeading} id={`catalog-${heading.toLowerCase()}`}>
        {heading}
      </h2>
      <p className={styles.sectionDescription}>{description}</p>

      {actionError && (
        <p className={styles.actionError} role="alert">
          {actionError}
        </p>
      )}

      {isPending && (
        <p className={styles.state} role="status">
          Loading {heading.toLowerCase()}…
        </p>
      )}

      {isError && (
        <p className={styles.actionError} role="alert">
          Could not load {heading.toLowerCase()}. {error?.message}
        </p>
      )}

      {!isPending && !isError && items && items.length === 0 && (
        <p className={styles.state}>No {heading.toLowerCase()} yet.</p>
      )}

      {!isPending && !isError && items && items.length > 0 && (
        <ul className={styles.list}>
          {items.map((item) => (
            <li key={item.id} className={styles.row}>
              {editingId === item.id ? (
                <form className={styles.editForm} onSubmit={submitRename}>
                  <input
                    className={styles.input}
                    aria-label={`New name for ${item.name}`}
                    value={editingName}
                    onChange={(event) => setEditingName(event.target.value)}
                    autoFocus
                  />
                  <button className={styles.primary} type="submit" disabled={!editingName.trim()}>
                    Save
                  </button>
                  <button
                    className={styles.secondary}
                    type="button"
                    onClick={() => setEditingId(null)}
                  >
                    Cancel
                  </button>
                </form>
              ) : (
                <>
                  <div className={styles.rowMain}>
                    <span className={styles.itemName}>{item.name}</span>
                    {renderExtra?.(item)}
                  </div>
                  <div className={styles.rowActions}>
                    <button
                      className={styles.secondary}
                      type="button"
                      onClick={() => {
                        setEditingId(item.id)
                        setEditingName(item.name)
                        setActionError(null)
                      }}
                    >
                      Rename
                    </button>
                    <button
                      className={styles.danger}
                      type="button"
                      onClick={() => remove(item)}
                      aria-label={`Delete ${item.name}`}
                    >
                      Delete
                    </button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      <form className={styles.createForm} onSubmit={submitNew}>
        <input
          className={styles.input}
          aria-label={`New ${heading.toLowerCase().replace(/s$/, '')} name`}
          placeholder={placeholder}
          value={newName}
          onChange={(event) => setNewName(event.target.value)}
        />
        <button className={styles.primary} type="submit" disabled={!newName.trim()}>
          Add
        </button>
      </form>
    </section>
  )
}
