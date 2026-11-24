import { Fragment, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createPermissionTemplate,
  fetchPermissionCatalogue,
  fetchPermissionTemplates,
  updatePermissionTemplate,
} from '../api/business'
import type { PermissionCatalogueEntry, PermissionTemplate } from '../api/models'
import {
  Badge,
  Button,
  Checkbox,
  ErrorState,
  FormField,
  Input,
  Modal,
  PageHeader,
  Skeleton,
  StatusPill,
  Tabs,
} from '../components/ui'

/**
 * Permission templates management with a sticky-header module matrix and per-template editors.
 */
export default function PermissionTemplatesPage() {
  const queryClient = useQueryClient()
  const [createOpen, setCreateOpen] = useState(false)

  const templatesQuery = useQuery({
    queryKey: ['permission-templates'],
    queryFn: fetchPermissionTemplates,
  })
  const catalogueQuery = useQuery({
    queryKey: ['permission-catalogue'],
    queryFn: fetchPermissionCatalogue,
  })

  const templates = templatesQuery.data ?? []
  const catalogue = catalogueQuery.data ?? []

  return (
    <div className="space-y-6">
      <PageHeader
        title="Permission templates"
        description="Named sets of permissions assigned to staff and manager accounts."
        actions={<Button onClick={() => setCreateOpen(true)}>Add a template</Button>}
      />

      {templatesQuery.isPending || catalogueQuery.isPending ? (
        <div role="status" aria-label="Loading permission templates" className="space-y-4">
          <Skeleton className="h-64 w-full rounded-xl" />
        </div>
      ) : null}

      {templatesQuery.error ? (
        <ErrorState
          title="The templates could not be loaded."
          error={templatesQuery.error}
          onRetry={() => templatesQuery.refetch()}
        />
      ) : null}

      {templatesQuery.isSuccess ? (
        <Tabs
          defaultTab="matrix"
          tabs={[
            {
              id: 'matrix',
              label: 'Permission matrix',
              content: (
                <PermissionMatrix
                  key={templates.map((t) => `${t.id}:${t.permissions.join(',')}`).join('|')}
                  templates={templates}
                  catalogue={catalogue}
                  catalogueError={catalogueQuery.error}
                  onSaved={() => queryClient.invalidateQueries({ queryKey: ['permission-templates'] })}
                />
              ),
            },
            {
              id: 'details',
              label: 'Template details',
              badge: <Badge tone="neutral">{templates.length}</Badge>,
              content: (
                <div className="space-y-4">
                  {templates.map((template) => (
                    <TemplateEditor
                      key={templateKey(template)}
                      template={template}
                      catalogue={catalogue}
                      catalogueError={catalogueQuery.error}
                      queryClient={queryClient}
                    />
                  ))}
                </div>
              ),
            },
          ]}
        />
      ) : null}

      <CreateTemplateModal open={createOpen} onClose={() => setCreateOpen(false)} />
    </div>
  )
}

function PermissionMatrix({
  templates,
  catalogue,
  catalogueError,
  onSaved,
}: {
  templates: PermissionTemplate[]
  catalogue: PermissionCatalogueEntry[]
  catalogueError: Error | null
  onSaved: () => void
}) {
  const [drafts, setDrafts] = useState<Record<string, string[]>>(() => {
    const map: Record<string, string[]> = {}
    for (const template of templates) {
      map[template.id] = [...template.permissions]
    }
    return map
  })

  const saveMutation = useMutation({
    mutationFn: async () => {
      const changedTemplates = templates.filter((template) => {
        const current = drafts[template.id] ?? []
        const original = template.permissions
        return (
          current.length !== original.length ||
          current.some((code) => !original.includes(code))
        )
      })
      for (const template of changedTemplates) {
        await updatePermissionTemplate(template.id, {
          name: template.name,
          description: template.description ?? '',
          permissions: drafts[template.id] ?? [],
          active: template.isActive,
        })
      }
    },
    onSuccess: onSaved,
  })

  const dirtyCount = useMemo(
    () =>
      templates.filter((template) => {
        const current = drafts[template.id] ?? []
        const original = template.permissions
        return (
          current.length !== original.length ||
          current.some((code) => !original.includes(code))
        )
      }).length,
    [templates, drafts],
  )

  function togglePermission(templateId: string, code: string, checked: boolean) {
    setDrafts((prev) => {
      const existing = prev[templateId] ?? []
      const next = checked
        ? Array.from(new Set([...existing, code]))
        : existing.filter((item) => item !== code)
      return { ...prev, [templateId]: next }
    })
  }

  function toggleModuleForTemplate(
    templateId: string,
    grantableCodes: string[],
    checked: boolean,
  ) {
    setDrafts((prev) => {
      const existing = prev[templateId] ?? []
      const next = checked
        ? Array.from(new Set([...existing, ...grantableCodes]))
        : existing.filter((item) => !grantableCodes.includes(item))
      return { ...prev, [templateId]: next }
    })
  }

  if (catalogueError) {
    return (
      <ErrorState
        title="The permission catalogue could not be loaded."
        error={catalogueError}
      />
    )
  }

  return (
    <div className="space-y-4">
      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white shadow-xs">
        <div className="max-h-[70vh] overflow-auto">
          <table className="min-w-full border-separate border-spacing-0 text-sm">
            <thead className="sticky top-0 z-20 bg-slate-50 shadow-xs">
              <tr>
                <th
                  scope="col"
                  className="sticky left-0 z-30 border-b border-slate-200 bg-slate-50 px-4 py-3 text-left text-xs font-semibold tracking-wide text-slate-600 uppercase"
                >
                  Permission
                </th>
                {templates.map((template) => (
                  <th
                    key={template.id}
                    scope="col"
                    className="min-w-[140px] border-b border-l border-slate-200 bg-slate-50 px-3 py-3 text-center"
                  >
                    <div className="text-xs font-semibold text-slate-900">{template.name}</div>
                    <div className="mt-0.5 font-mono text-[11px] font-normal text-slate-500">
                      {template.code}
                    </div>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {catalogue.map((module) => {
                const grantableCodes = module.permissions
                  .filter((p) => !p.ownerOnly)
                  .map((p) => p.code)

                return (
                  <Fragment key={module.code}>
                    <tr className="bg-slate-100/90">
                      <th
                        scope="row"
                        className="sticky left-0 z-10 border-y border-slate-200 bg-slate-100 px-4 py-2 text-left text-xs font-semibold tracking-wide text-slate-700 uppercase"
                      >
                        {module.label}
                      </th>
                      {templates.map((template) => {
                        const selected = drafts[template.id] ?? []
                        const allSelected =
                          grantableCodes.length > 0 &&
                          grantableCodes.every((code) => selected.includes(code))
                        return (
                          <td
                            key={template.id}
                            className="border-y border-l border-slate-200 px-3 py-2 text-center"
                          >
                            {grantableCodes.length > 0 ? (
                              <label className="inline-flex cursor-pointer items-center gap-1.5 text-[11px] font-medium text-slate-700">
                                <Checkbox
                                  checked={allSelected}
                                  disabled={saveMutation.isPending}
                                  onChange={(e) =>
                                    toggleModuleForTemplate(
                                      template.id,
                                      grantableCodes,
                                      e.target.checked,
                                    )
                                  }
                                  aria-label={`Select all in ${module.label} for ${template.name}`}
                                />
                                <span>All</span>
                              </label>
                            ) : null}
                          </td>
                        )
                      })}
                    </tr>
                    {module.permissions.map((permission) => (
                      <tr key={permission.code} className="hover:bg-slate-50/70">
                        <td className="sticky left-0 z-10 border-b border-slate-100 bg-white px-4 py-2.5">
                          <div className="flex items-center gap-2">
                            <span className="font-mono text-xs font-medium text-slate-800">
                              {permission.code}
                            </span>
                            {permission.ownerOnly ? (
                              <Badge tone="neutral">Owner only</Badge>
                            ) : null}
                          </div>
                          <p className="text-xs text-slate-500">{permission.description}</p>
                        </td>
                        {templates.map((template) => {
                          const checked = (drafts[template.id] ?? []).includes(permission.code)
                          return (
                            <td
                              key={template.id}
                              className="border-b border-l border-slate-100 px-3 py-2.5 text-center"
                            >
                              <Checkbox
                                checked={checked}
                                disabled={permission.ownerOnly || saveMutation.isPending}
                                aria-label={`${permission.code} for ${template.name}`}
                                onChange={(e) =>
                                  togglePermission(
                                    template.id,
                                    permission.code,
                                    e.target.checked,
                                  )
                                }
                              />
                            </td>
                          )
                        })}
                      </tr>
                    ))}
                  </Fragment>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>

      {saveMutation.isError ? (
        <ErrorState title="Could not save matrix" error={saveMutation.error} />
      ) : null}

      {saveMutation.isSuccess ? (
        <p role="status" className="text-sm font-medium text-emerald-700">
          Matrix changes saved.
        </p>
      ) : null}

      <div className="flex flex-wrap items-center gap-3">
        <Button
          onClick={() => saveMutation.mutate()}
          disabled={dirtyCount === 0}
          isLoading={saveMutation.isPending}
          loadingText="Saving..."
        >
          Save matrix changes
        </Button>
        <span className="text-xs text-slate-500">
          {dirtyCount === 0
            ? 'No unsaved changes.'
            : `${dirtyCount} template(s) modified.`}
        </span>
      </div>
    </div>
  )
}

function CreateTemplateModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')

  const mutation = useMutation({
    mutationFn: () =>
      createPermissionTemplate({
        code: code.trim(),
        name: name.trim(),
        description: description.trim() || undefined,
        permissions: [],
      }),
    onSuccess: () => {
      setCode('')
      setName('')
      setDescription('')
      queryClient.invalidateQueries({ queryKey: ['permission-templates'] })
      onClose()
    },
  })

  return (
    <Modal open={open} onClose={onClose} title="New template">
      <form
        onSubmit={(event) => {
          event.preventDefault()
          mutation.mutate()
        }}
        className="space-y-4"
      >
        <div className="grid gap-3 sm:grid-cols-2">
          <FormField label="Code" required>
            <Input
              value={code}
              onChange={(event) => setCode(event.target.value.toUpperCase())}
              required
            />
          </FormField>
          <FormField label="Name" required>
            <Input value={name} onChange={(e) => setName(e.target.value)} required />
          </FormField>
        </div>

        <FormField label="Description">
          <Input value={description} onChange={(e) => setDescription(e.target.value)} />
        </FormField>

        {mutation.isError ? (
          <ErrorState title="Could not create template" error={mutation.error} />
        ) : null}

        <div className="flex justify-end gap-2 pt-2">
          <Button variant="outline" onClick={onClose}>
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={!code.trim() || !name.trim()}
            isLoading={mutation.isPending}
            loadingText="Saving..."
          >
            Create the template
          </Button>
        </div>
      </form>
    </Modal>
  )
}

function TemplateEditor({
  template,
  catalogue,
  catalogueError,
  queryClient,
}: {
  template: PermissionTemplate
  catalogue: PermissionCatalogueEntry[]
  catalogueError: Error | null
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const [selected, setSelected] = useState<string[]>(template.permissions)
  const [name, setName] = useState(template.name)
  const [description, setDescription] = useState(template.description ?? '')
  const [active, setActive] = useState(template.isActive)

  const mutation = useMutation({
    mutationFn: () =>
      updatePermissionTemplate(template.id, {
        name: name.trim(),
        description: description.trim(),
        permissions: selected,
        active,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['permission-templates'] }),
  })

  return (
    <section
      aria-labelledby={`template-${template.id}`}
      className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex flex-wrap items-center gap-2">
            <h2 id={`template-${template.id}`} className="text-base font-semibold text-slate-900">
              {template.name}
            </h2>
            <StatusPill status={active ? 'ACTIVE' : 'INACTIVE'} />
            {template.isSystem ? <Badge tone="info">System</Badge> : null}
          </div>
          <p className="mt-0.5 font-mono text-xs text-slate-500">{template.code}</p>
        </div>
        <Checkbox
          checked={active}
          onChange={(event) => setActive(event.target.checked)}
          label="May be given to an account"
        />
      </div>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <FormField label="Name">
          <Input value={name} onChange={(e) => setName(e.target.value)} />
        </FormField>
        <FormField label="Description">
          <Input value={description} onChange={(e) => setDescription(e.target.value)} />
        </FormField>
      </div>

      <div className="mt-5 space-y-4">
        {catalogue.map((module) => {
          const grantableCodes = module.permissions
            .filter((p) => !p.ownerOnly)
            .map((p) => p.code)
          const allSelected =
            grantableCodes.length > 0 &&
            grantableCodes.every((code) => selected.includes(code))

          return (
            <fieldset key={module.code} className="rounded-lg border border-slate-200 p-3.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <legend className="text-xs font-semibold tracking-wide text-slate-600 uppercase">
                  {module.label}
                </legend>
                {grantableCodes.length > 0 ? (
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() =>
                      setSelected((prev) =>
                        allSelected
                          ? prev.filter((code) => !grantableCodes.includes(code))
                          : Array.from(new Set([...prev, ...grantableCodes])),
                      )
                    }
                  >
                    {allSelected ? 'Clear module' : 'Select all in module'}
                  </Button>
                ) : null}
              </div>
              <ul className="mt-2 grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
                {module.permissions.map((permission) => (
                  <li key={permission.code}>
                    <Checkbox
                      id={`${template.id}-${permission.code}`}
                      checked={selected.includes(permission.code)}
                      disabled={permission.ownerOnly || mutation.isPending}
                      onChange={(event) =>
                        setSelected((previous) =>
                          event.target.checked
                            ? [...previous, permission.code]
                            : previous.filter((value) => value !== permission.code),
                        )
                      }
                      label={
                        <span className="font-mono text-xs">
                          {permission.code}
                          {permission.ownerOnly ? (
                            <span className="ml-1 font-sans text-slate-400">(owner only)</span>
                          ) : null}
                        </span>
                      }
                    />
                  </li>
                ))}
              </ul>
            </fieldset>
          )
        })}
      </div>

      {catalogueError ? (
        <p className="mt-3 text-xs text-slate-500">
          The permission catalogue could not be read, so the list of permissions is missing.
        </p>
      ) : null}

      {mutation.isError ? (
        <div className="mt-4">
          <ErrorState title="Could not save template" error={mutation.error} />
        </div>
      ) : null}
      {mutation.isSuccess ? (
        <p role="status" className="mt-3 text-sm font-medium text-emerald-700">
          The template was saved.
        </p>
      ) : null}

      <div className="mt-4">
        <Button onClick={() => mutation.mutate()} isLoading={mutation.isPending} loadingText="Saving...">
          Save the template
        </Button>
      </div>
    </section>
  )
}

function templateKey(template: PermissionTemplate): string {
  return [
    template.id,
    template.name,
    template.description ?? '',
    template.isActive ? '1' : '0',
    template.permissions.join('|'),
  ].join('~')
}
