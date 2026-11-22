import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSettings, saveSettings } from '../api/business'
import { ApiError } from '../api/types'
import type { SettingEntry, SettingsGroups } from '../api/models'
import { useSession } from '../session/sessionContext'

/**
 * The groups of the settings screen, in the order they are shown.
 */
const GROUP_ORDER: Array<keyof SettingsGroups> = ['BUSINESS', 'RECEIPT', 'TAX', 'THRESHOLDS']

/**
 * The title of each group.
 */
const GROUP_TITLES: Record<keyof SettingsGroups, string> = {
  BUSINESS: 'Business',
  RECEIPT: 'Receipt',
  TAX: 'Tax',
  THRESHOLDS: 'Thresholds',
}

/**
 * The business settings screen.
 *
 * It shows every business value in one place: the name of the shop, the receipt footer, the tax
 * rate and the thresholds that the later screens act on.
 *
 * The values are not hardcoded in the frontend. They come from the `settings` table through the
 * API, and the backend validates every value before it writes anything, so the screen cannot save
 * a value the business rules refuse.
 *
 * Only the owner may write. A manager may read the screen, but the save button is not there.
 *
 * @returns the settings screen element
 */
export default function SettingsPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const isOwner = user?.accountType === 'OWNER'

  const query = useQuery({ queryKey: ['settings'], queryFn: fetchSettings })

  if (query.isPending) {
    return (
      <div
        role="status"
        className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600"
      >
        Loading the settings...
      </div>
    )
  }

  if (query.error) {
    return (
      <div
        role="alert"
        className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
      >
        <p className="font-medium">The settings could not be loaded.</p>
        <p className="mt-1">{query.error.message}</p>
      </div>
    )
  }

  const groups = query.data?.groups

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Settings</h1>
        <p className="mt-1 text-sm text-slate-600">
          {isOwner
            ? 'The business values of Merqo. Every screen reads them from here.'
            : 'The business values of Merqo. Only the owner may change them.'}
        </p>
      </div>

      {/*
        The form is a child component, so the draft of the values lives in its own state. The key
        carries the saved values, which means the form is rebuilt when the server sends different
        values and keeps the draft while the user is typing.
      */}
      <SettingsForm
        key={savedValuesKey(groups)}
        groups={groups}
        isOwner={isOwner}
        onSaved={() => queryClient.invalidateQueries({ queryKey: ['settings'] })}
      />
    </div>
  )
}

/**
 * The form of the settings screen.
 *
 * @param props the groups, whether the caller is the owner and the callback after a save
 * @returns the form element
 */
function SettingsForm({
  groups,
  isOwner,
  onSaved,
}: {
  groups: SettingsGroups | undefined
  isOwner: boolean
  onSaved: () => void
}) {
  const saved = useMemo(() => flattenGroups(groups), [groups])

  // The draft holds the values while the user edits them. The saved values stay untouched until
  // the save succeeds, so a failed save never loses what is on the screen.
  const [draft, setDraft] = useState<Record<string, string>>(saved)

  const mutation = useMutation({
    mutationFn: saveSettings,
    onSuccess: onSaved,
  })

  const changed: Record<string, string> = {}
  for (const [key, value] of Object.entries(draft)) {
    if (saved[key] !== value) {
      changed[key] = value
    }
  }
  const changeCount = Object.keys(changed).length

  /**
   * Changes one value in the draft.
   *
   * @param key the key of the setting
   * @param value the new value
   */
  function handleChange(key: string, value: string) {
    setDraft((previous) => ({ ...previous, [key]: value }))
  }

  /**
   * Puts the saved values back into the draft.
   */
  function handleReset() {
    setDraft(saved)
  }

  return (
    <>
      {mutation.isError ? (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-4 text-sm text-rose-800"
        >
          <p className="font-medium">The settings were not saved.</p>
          <p className="mt-1">{readError(mutation.error)}</p>
        </div>
      ) : null}

      {mutation.isSuccess ? (
        <div
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 px-6 py-4 text-sm text-emerald-800"
        >
          The settings were saved.
        </div>
      ) : null}

      {GROUP_ORDER.map((group) => (
        <SettingsGroup
          key={group}
          title={GROUP_TITLES[group]}
          entries={groups?.[group] ?? []}
          draft={draft}
          isOwner={isOwner}
          onChange={handleChange}
        />
      ))}

      {isOwner ? (
        <div className="flex items-center gap-3 border-t border-slate-200 pt-4">
          <button
            type="button"
            onClick={() => mutation.mutate(changed)}
            disabled={changeCount === 0 || mutation.isPending}
            className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {mutation.isPending ? 'Saving...' : 'Save the settings'}
          </button>
          <button
            type="button"
            onClick={handleReset}
            disabled={changeCount === 0 || mutation.isPending}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-60"
          >
            Undo the changes
          </button>
          <span className="text-xs text-slate-500">
            {changeCount === 0 ? 'No change to save.' : `${changeCount} value(s) changed.`}
          </span>
        </div>
      ) : null}
    </>
  )
}

/**
 * One group of settings.
 *
 * @param props the title, the entries, the draft and the change handler
 * @returns the group element
 */
function SettingsGroup({
  title,
  entries,
  draft,
  isOwner,
  onChange,
}: {
  title: string
  entries: SettingEntry[]
  draft: Record<string, string>
  isOwner: boolean
  onChange: (key: string, value: string) => void
}) {
  if (entries.length === 0) {
    return null
  }

  return (
    <section aria-labelledby={`settings-${title.toLowerCase()}`} className="space-y-3">
      <h2 id={`settings-${title.toLowerCase()}`} className="text-sm font-semibold text-slate-700">
        {title}
      </h2>
      <div className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <div className="divide-y divide-slate-100">
          {entries.map((entry) => (
            <SettingRow
              key={entry.key}
              entry={entry}
              value={draft[entry.key] ?? entry.value}
              isOwner={isOwner}
              onChange={onChange}
            />
          ))}
        </div>
      </div>
    </section>
  )
}

/**
 * One setting.
 *
 * @param props the entry, the value and the change handler
 * @returns the row element
 */
function SettingRow({
  entry,
  value,
  isOwner,
  onChange,
}: {
  entry: SettingEntry
  value: string
  isOwner: boolean
  onChange: (key: string, value: string) => void
}) {
  const inputId = `setting-${entry.key.replace('.', '-')}`

  return (
    <div className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="sm:mr-6">
        <label htmlFor={inputId} className="block text-sm font-medium text-slate-800">
          {entry.label}
        </label>
        <p className="mt-0.5 font-mono text-xs text-slate-400">{entry.key}</p>
      </div>
      <div className="sm:w-72">
        <SettingInput
          id={inputId}
          entry={entry}
          value={value}
          isOwner={isOwner}
          onChange={(newValue) => onChange(entry.key, newValue)}
        />
      </div>
    </div>
  )
}

/**
 * The input of one setting, chosen by the type of the value.
 *
 * @param props the entry, the value and the change handler
 * @returns the input element
 */
function SettingInput({
  id,
  entry,
  value,
  isOwner,
  onChange,
}: {
  id: string
  entry: SettingEntry
  value: string
  isOwner: boolean
  onChange: (value: string) => void
}) {
  const editable = isOwner && !entry.readOnly
  const className =
    'w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100 disabled:bg-slate-50 disabled:text-slate-500'

  if (entry.type === 'BOOLEAN') {
    return (
      <select
        id={id}
        value={value === 'true' ? 'true' : 'false'}
        disabled={!editable}
        onChange={(event) => onChange(event.target.value)}
        className={className}
      >
        <option value="false">No</option>
        <option value="true">Yes</option>
      </select>
    )
  }

  if (entry.type === 'PAPER_WIDTH') {
    return (
      <select
        id={id}
        value={value}
        disabled={!editable}
        onChange={(event) => onChange(event.target.value)}
        className={className}
      >
        <option value="58">58 mm</option>
        <option value="80">80 mm</option>
      </select>
    )
  }

  return (
    <input
      id={id}
      type={entry.type === 'TEXT' ? 'text' : 'number'}
      step={entry.type === 'AMOUNT' ? '0.01' : 'any'}
      value={value}
      disabled={!editable}
      onChange={(event) => onChange(event.target.value)}
      className={className}
    />
  )
}

/**
 * Flattens the groups of settings into one map of key and value.
 *
 * @param groups the groups, may be undefined
 * @returns the values
 */
function flattenGroups(groups: SettingsGroups | undefined): Record<string, string> {
  const values: Record<string, string> = {}
  if (!groups) {
    return values
  }
  for (const group of GROUP_ORDER) {
    for (const entry of groups[group]) {
      values[entry.key] = entry.value
    }
  }
  return values
}

/**
 * Builds the key of the form from the saved values.
 *
 * The form is rebuilt when the server sends different values, and it keeps the draft of the user
 * while nothing changed on the server.
 *
 * @param groups the groups, may be undefined
 * @returns the key
 */
function savedValuesKey(groups: SettingsGroups | undefined): string {
  return Object.entries(flattenGroups(groups))
    .map(([key, value]) => `${key}=${value}`)
    .join('|')
}

/**
 * Turns any failure into one sentence the user can act on.
 *
 * @param caught the thrown value
 * @returns the message to show
 */
function readError(caught: unknown): string {
  if (caught instanceof ApiError) {
    return caught.message
  }
  return 'Something went wrong. Try again.'
}
