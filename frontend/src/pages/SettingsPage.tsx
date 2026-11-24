import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { fetchSettings, saveSettings } from '../api/business'
import { ApiError } from '../api/types'
import type { SettingEntry, SettingsGroups } from '../api/models'
import { useSession } from '../session/sessionContext'
import { Button, ErrorState, Input, PageHeader, Select, Skeleton } from '../components/ui'

const GROUP_ORDER: Array<keyof SettingsGroups> = [
  'BUSINESS',
  'RECEIPT',
  'TAX',
  'INVENTORY',
  'THRESHOLDS',
]

const GROUP_TITLES: Record<keyof SettingsGroups, string> = {
  BUSINESS: 'Business',
  RECEIPT: 'Receipt',
  TAX: 'Tax',
  INVENTORY: 'Inventory',
  THRESHOLDS: 'Thresholds',
}

/**
 * The business settings screen.
 */
export default function SettingsPage() {
  const { user } = useSession()
  const queryClient = useQueryClient()
  const isOwner = user?.accountType === 'OWNER'

  const query = useQuery({ queryKey: ['settings'], queryFn: fetchSettings })

  if (query.isPending) {
    return (
      <div role="status" aria-label="Loading settings" className="space-y-4">
        <Skeleton className="h-16 w-full rounded-xl" />
        <Skeleton className="h-56 w-full rounded-xl" />
      </div>
    )
  }

  if (query.error) {
    return (
      <ErrorState
        title="The settings could not be loaded."
        error={query.error}
        onRetry={() => query.refetch()}
      />
    )
  }

  const groups = query.data?.groups

  return (
    <div className="space-y-6">
      <PageHeader
        title="Settings"
        description={
          isOwner
            ? 'Business-wide configuration values shared across all locations.'
            : 'Business-wide configuration values. Only the owner may change them.'
        }
      />

      <SettingsForm
        key={savedValuesKey(groups)}
        groups={groups}
        isOwner={isOwner}
        onSaved={() => queryClient.invalidateQueries({ queryKey: ['settings'] })}
      />
    </div>
  )
}

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

  function handleChange(key: string, value: string) {
    setDraft((previous) => ({ ...previous, [key]: value }))
  }

  function handleReset() {
    setDraft(saved)
  }

  return (
    <>
      {mutation.isError ? (
        <ErrorState
          title="The settings were not saved."
          message={readError(mutation.error)}
          correlationId={
            mutation.error instanceof ApiError ? mutation.error.correlationId : undefined
          }
        />
      ) : null}

      {mutation.isSuccess ? (
        <div
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 px-5 py-3.5 text-sm font-medium text-emerald-900"
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
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-200 bg-white p-4 shadow-xs">
          <Button
            onClick={() => mutation.mutate(changed)}
            disabled={changeCount === 0}
            isLoading={mutation.isPending}
            loadingText="Saving..."
          >
            Save the settings
          </Button>
          <Button
            variant="outline"
            onClick={handleReset}
            disabled={changeCount === 0 || mutation.isPending}
          >
            Undo the changes
          </Button>
          <span className="text-xs text-slate-500">
            {changeCount === 0 ? 'No change to save.' : `${changeCount} value(s) changed.`}
          </span>
        </div>
      ) : null}
    </>
  )
}

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
      <h2 id={`settings-${title.toLowerCase()}`} className="text-sm font-semibold text-slate-800">
        {title}
      </h2>
      <div className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white shadow-xs">
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
    </section>
  )
}

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
  const inputId = `setting-${entry.key.replaceAll('.', '-')}`

  return (
    <div className="flex flex-col gap-2 px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
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

  if (entry.type === 'BOOLEAN') {
    return (
      <Select
        id={id}
        value={value === 'true' ? 'true' : 'false'}
        disabled={!editable}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="false">No</option>
        <option value="true">Yes</option>
      </Select>
    )
  }

  if (entry.type === 'PAPER_WIDTH') {
    return (
      <Select
        id={id}
        value={value}
        disabled={!editable}
        onChange={(event) => onChange(event.target.value)}
      >
        <option value="58">58 mm</option>
        <option value="80">80 mm</option>
      </Select>
    )
  }

  return (
    <Input
      id={id}
      type={entry.type === 'TEXT' ? 'text' : 'number'}
      step={entry.type === 'AMOUNT' ? '0.01' : 'any'}
      value={value}
      disabled={!editable}
      onChange={(event) => onChange(event.target.value)}
    />
  )
}

function flattenGroups(groups: SettingsGroups | undefined): Record<string, string> {
  const values: Record<string, string> = {}
  if (!groups) {
    return values
  }
  for (const group of GROUP_ORDER) {
    for (const entry of groups[group] ?? []) {
      values[entry.key] = entry.value
    }
  }
  return values
}

function savedValuesKey(groups: SettingsGroups | undefined): string {
  return Object.entries(flattenGroups(groups))
    .map(([key, value]) => `${key}=${value}`)
    .join('|')
}

function readError(caught: unknown): string {
  if (caught instanceof ApiError) {
    return caught.message
  }
  return 'Something went wrong. Try again.'
}
