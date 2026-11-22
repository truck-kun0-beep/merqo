import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  createPermissionTemplate,
  fetchPermissionCatalogue,
  fetchPermissionTemplates,
  updatePermissionTemplate,
} from '../api/business'
import { ApiError } from '../api/types'
import type { PermissionTemplate } from '../api/models'

/**
 * The permission template screen.
 *
 * A template is a named set of permissions. The owner writes them here, and the
 * accounts that hold a template get every permission of that template.
 *
 * Only the owner may open this screen: the backend answers everybody else with
 * a 403, and the route guard hides the link.
 *
 * @returns the template screen element
 */
export default function PermissionTemplatesPage() {
  const queryClient = useQueryClient()

  const templatesQuery = useQuery({
    queryKey: ['permission-templates'],
    queryFn: fetchPermissionTemplates,
  })

  const templates = templatesQuery.data ?? []

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
          Permission templates
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          A template is a named set of permissions. Give a template to an account and the account
          gets every permission inside it.
        </p>
      </div>

      <CreateTemplateForm />

      {templatesQuery.isPending ? (
        <div
          role="status"
          className="rounded-xl border border-slate-200 bg-white px-6 py-8 text-center text-sm text-slate-600"
        >
          Loading the templates...
        </div>
      ) : null}

      {templatesQuery.error ? (
        <div
          role="alert"
          className="rounded-xl border border-rose-200 bg-rose-50 px-6 py-6 text-sm text-rose-800"
        >
          <p className="font-medium">The templates could not be loaded.</p>
          <p className="mt-1">{templatesQuery.error.message}</p>
        </div>
      ) : null}

      {templates.map((template) => (
        <TemplateEditor key={templateKey(template)} template={template} queryClient={queryClient} />
      ))}
    </div>
  )
}

/**
 * The form that creates a template.
 *
 * @returns the form element
 */
function CreateTemplateForm() {
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
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
      setOpen(false)
      queryClient.invalidateQueries({ queryKey: ['permission-templates'] })
    },
  })

  if (!open) {
    return (
      <div>
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800"
        >
          Add a template
        </button>
      </div>
    )
  }

  return (
    <form
      className="space-y-3 rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
      onSubmit={(event) => {
        event.preventDefault()
        mutation.mutate()
      }}
    >
      <h2 className="text-sm font-semibold text-slate-700">New template</h2>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Code</span>
          <input
            type="text"
            value={code}
            onChange={(event) => setCode(event.target.value.toUpperCase())}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-sm text-slate-700">
          <span className="block text-xs font-medium text-slate-500">Name</span>
          <input
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
      </div>

      <label className="block text-sm text-slate-700">
        <span className="block text-xs font-medium text-slate-500">Description</span>
        <input
          type="text"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
        />
      </label>

      {mutation.isError ? (
        <p role="alert" className="text-sm text-rose-700">
          {readError(mutation.error)}
        </p>
      ) : null}

      <div className="flex items-center gap-3">
        <button
          type="submit"
          disabled={mutation.isPending || !code.trim() || !name.trim()}
          className="rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
        >
          {mutation.isPending ? 'Saving...' : 'Create the template'}
        </button>
        <button
          type="button"
          onClick={() => setOpen(false)}
          className="rounded-md border border-slate-300 px-4 py-2 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100"
        >
          Cancel
        </button>
      </div>
    </form>
  )
}

/**
 * The editor of one template.
 *
 * @param props the template and the query client
 * @returns the editor element
 */
function TemplateEditor({
  template,
  queryClient,
}: {
  template: PermissionTemplate
  queryClient: ReturnType<typeof useQueryClient>
}) {
  const catalogueQuery = useQuery({
    queryKey: ['permission-catalogue'],
    queryFn: fetchPermissionCatalogue,
  })

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

  const grouped = useMemo(
    () =>
      (catalogueQuery.data ?? []).map((module) => ({
        label: module.label,
        permissions: module.permissions,
      })),
    [catalogueQuery.data],
  )

  return (
    <section
      aria-labelledby={`template-${template.id}`}
      className="rounded-xl border border-slate-200 bg-white p-4 shadow-sm"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 id={`template-${template.id}`} className="text-sm font-semibold text-slate-900">
            {template.name}
          </h2>
          <p className="mt-0.5 font-mono text-xs text-slate-500">{template.code}</p>
          {template.isSystem ? (
            <p className="mt-1 text-xs text-slate-500">This is one of the starting templates.</p>
          ) : null}
        </div>
        <label className="flex items-center gap-2 text-xs text-slate-600">
          <input
            type="checkbox"
            checked={active}
            onChange={(event) => setActive(event.target.checked)}
          />
          May be given to an account
        </label>
      </div>

      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <label className="text-xs text-slate-600">
          <span className="block font-medium text-slate-500">Name</span>
          <input
            type="text"
            value={name}
            onChange={(event) => setName(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
        <label className="text-xs text-slate-600">
          <span className="block font-medium text-slate-500">Description</span>
          <input
            type="text"
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-900 outline-none focus:border-merqo-500 focus:ring-2 focus:ring-merqo-100"
          />
        </label>
      </div>

      <div className="mt-4 space-y-3">
        {grouped.map((module) => (
          <fieldset key={module.label}>
            <legend className="text-xs font-semibold tracking-wide text-slate-500 uppercase">
              {module.label}
            </legend>
            <ul className="mt-1 grid gap-1 sm:grid-cols-2">
              {module.permissions.map((permission) => (
                <li key={permission.code} className="flex items-center gap-2 text-xs">
                  <input
                    id={`${template.id}-${permission.code}`}
                    type="checkbox"
                    checked={selected.includes(permission.code)}
                    disabled={permission.ownerOnly || mutation.isPending}
                    onChange={(event) =>
                      setSelected((previous) =>
                        event.target.checked
                          ? [...previous, permission.code]
                          : previous.filter((value) => value !== permission.code),
                      )
                    }
                  />
                  <label htmlFor={`${template.id}-${permission.code}`} className="text-slate-700">
                    <span className="font-mono">{permission.code}</span>
                    {permission.ownerOnly ? (
                      <span className="ml-1 text-slate-400">(owner only)</span>
                    ) : null}
                  </label>
                </li>
              ))}
            </ul>
          </fieldset>
        ))}
      </div>

      {catalogueQuery.error ? (
        <p className="mt-3 text-xs text-slate-500">
          The permission catalogue could not be read, so the list of permissions is missing.
        </p>
      ) : null}

      {mutation.isError ? (
        <p role="alert" className="mt-3 text-sm text-rose-700">
          {readError(mutation.error)}
        </p>
      ) : null}
      {mutation.isSuccess ? (
        <p role="status" className="mt-3 text-sm text-emerald-700">
          The template was saved.
        </p>
      ) : null}

      <button
        type="button"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
        className="mt-4 rounded-md bg-merqo-700 px-4 py-2 text-sm font-semibold text-white transition-colors hover:bg-merqo-800 disabled:opacity-60"
      >
        {mutation.isPending ? 'Saving...' : 'Save the template'}
      </button>
    </section>
  )
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

/**
 * Builds the key of the editor of one template.
 *
 * The editor holds a draft of the template values. It is rebuilt when the
 * server sends different values, so the draft never shows something the server
 * does not know.
 *
 * @param template the template
 * @returns the key
 */
function templateKey(template: PermissionTemplate): string {
  return [
    template.id,
    template.name,
    template.description ?? '',
    template.isActive ? '1' : '0',
    template.permissions.join('|'),
  ].join('~')
}
