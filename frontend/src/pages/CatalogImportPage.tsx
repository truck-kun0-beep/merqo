import { useState, type ChangeEvent } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { fetchLocations } from '../api/business'
import { commitImport, fetchImportTemplate, validateImport } from '../api/catalog'
import type { ImportCommitResult, ImportValidation } from '../api/models'
import { filterLocationsByPermission } from '../lib/scopes'
import { useSession } from '../session/sessionContext'
import {
  Badge,
  Button,
  ErrorState,
  FormField,
  PageHeader,
  Select,
  useToast,
} from '../components/ui'

/**
 * Two-step catalog CSV import: template download, validate (dry run with row errors), and commit.
 */
export default function CatalogImportPage() {
  const { user } = useSession()
  const { toast } = useToast()

  const [file, setFile] = useState<File | null>(null)
  const [openingLocationId, setOpeningLocationId] = useState('')
  const [validation, setValidation] = useState<ImportValidation | null>(null)
  const [commitResult, setCommitResult] = useState<ImportCommitResult | null>(null)

  const locationsQuery = useQuery({ queryKey: ['locations'], queryFn: fetchLocations })
  const adjustableLocations = filterLocationsByPermission(
    user,
    'INVENTORY_ADJUST',
    locationsQuery.data ?? [],
  )

  const templateMutation = useMutation({
    mutationFn: fetchImportTemplate,
    onSuccess: (csv) => {
      const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = 'merqo-catalog-template.csv'
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    },
  })

  const validateMutation = useMutation({
    mutationFn: () => validateImport(file!, openingLocationId || undefined),
    onSuccess: (result) => {
      setValidation(result)
      setCommitResult(null)
    },
  })

  const commitMutation = useMutation({
    mutationFn: () => commitImport(file!, openingLocationId || undefined),
    onSuccess: (result) => {
      setCommitResult(result)
      toast({ title: 'Catalog import applied', tone: 'success' })
    },
  })

  function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const picked = event.target.files?.[0] ?? null
    setFile(picked)
    setValidation(null)
    setCommitResult(null)
  }

  return (
    <div className="space-y-6">
      <PageHeader
        breadcrumbs={[{ label: 'Catalog', to: '/catalog/products' }, { label: 'CSV import' }]}
        title="Catalog CSV import"
        description="Validate your CSV file for row-level issues before committing changes."
        actions={
          <Button
            variant="outline"
            onClick={() => templateMutation.mutate()}
            isLoading={templateMutation.isPending}
          >
            Download CSV template
          </Button>
        }
      />

      {templateMutation.isError ? (
        <ErrorState title="Could not download template" error={templateMutation.error} />
      ) : null}

      <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <div className="grid gap-4 sm:grid-cols-2">
          <FormField label="CSV file" hint="UTF-8 CSV up to 1 MB and 2,000 rows." required>
            <input
              type="file"
              accept=".csv,text/csv"
              onChange={handleFileChange}
              className="block w-full rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm text-slate-700 file:mr-3 file:rounded file:border-0 file:bg-merqo-50 file:px-3 file:py-1 file:text-xs file:font-semibold file:text-merqo-800 hover:file:bg-merqo-100"
            />
          </FormField>

          <FormField
            label="Opening stock location (optional)"
            hint="Used only for variants with no movements at that location yet."
          >
            <Select
              value={openingLocationId}
              onChange={(e) => {
                setOpeningLocationId(e.target.value)
                setValidation(null)
              }}
            >
              <option value="">None (skip opening stock)</option>
              {adjustableLocations.map((loc) => (
                <option key={loc.id} value={loc.id}>
                  {loc.name} ({loc.code})
                </option>
              ))}
            </Select>
          </FormField>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <Button
            onClick={() => validateMutation.mutate()}
            disabled={!file}
            isLoading={validateMutation.isPending}
            loadingText="Validating..."
          >
            1. Validate file
          </Button>

          <Button
            variant="secondary"
            onClick={() => commitMutation.mutate()}
            disabled={!file || !validation?.isValid || Boolean(commitResult)}
            isLoading={commitMutation.isPending}
            loadingText="Importing..."
          >
            2. Confirm & import
          </Button>
        </div>

        {validateMutation.isError ? (
          <ErrorState title="Validation request failed" error={validateMutation.error} />
        ) : null}

        {commitMutation.isError ? (
          <ErrorState title="Import commit failed" error={commitMutation.error} />
        ) : null}
      </div>

      {validation ? (
        <section
          aria-labelledby="validation-heading"
          className="space-y-4 rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 id="validation-heading" className="text-base font-semibold text-slate-900">
              Validation summary
            </h2>
            <Badge tone={validation.isValid ? 'success' : 'danger'}>
              {validation.isValid ? 'Ready to import' : `${validation.errors.length} error(s)`}
            </Badge>
          </div>

          <dl className="grid gap-3 sm:grid-cols-4">
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <dt className="text-xs text-slate-500">Total rows</dt>
              <dd className="mt-1 text-lg font-semibold text-slate-900 tabular-nums">
                {validation.totalRows}
              </dd>
            </div>
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <dt className="text-xs text-slate-500">To create</dt>
              <dd className="mt-1 text-lg font-semibold text-slate-900 tabular-nums">
                {validation.toCreate}
              </dd>
            </div>
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <dt className="text-xs text-slate-500">To update</dt>
              <dd className="mt-1 text-lg font-semibold text-slate-900 tabular-nums">
                {validation.toUpdate}
              </dd>
            </div>
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <dt className="text-xs text-slate-500">Opening stock lines</dt>
              <dd className="mt-1 text-lg font-semibold text-slate-900 tabular-nums">
                {validation.openingStockLines}
              </dd>
            </div>
          </dl>

          {validation.errors.length > 0 ? (
            <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-4">
              <p className="text-sm font-semibold text-rose-950">
                Fix these issues before importing:
              </p>
              <ul className="mt-2 space-y-1 text-xs text-rose-900">
                {validation.errors.map((err, index) => (
                  <li key={`${err.row}-${index}`}>
                    <span className="font-mono font-semibold">Row {err.row}:</span> {err.message}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </section>
      ) : null}

      {commitResult ? (
        <div
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-5 text-sm text-emerald-950"
        >
          <p className="font-semibold">Import complete</p>
          <p className="mt-1 text-xs">
            Created {commitResult.created} · Updated {commitResult.updated} · Opening stock lines{' '}
            {commitResult.openingStockLines} · Total rows {commitResult.totalRows}
          </p>
          {commitResult.skipped.length > 0 ? (
            <p className="mt-1 text-xs">
              Skipped opening stock (already had movements): {commitResult.skipped.join(', ')}
            </p>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}
