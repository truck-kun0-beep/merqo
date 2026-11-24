import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  Button,
  ConfirmDialog,
  DataTable,
  DateText,
  Drawer,
  ErrorState,
  FormField,
  Input,
  Modal,
  MoneyText,
  StatusPill,
  Tabs,
  ToastProvider,
  useToast,
} from './index'
import { ApiError } from '../../api/types'

describe('UI design system components', () => {
  it('renders Button loading state and prevents clicks while loading', async () => {
    const onClick = vi.fn()
    render(
      <Button isLoading loadingText="Saving..." onClick={onClick}>
        Save
      </Button>,
    )
    const btn = screen.getByRole('button', { name: 'Saving...' })
    expect(btn).toBeDisabled()
    expect(btn).toHaveAttribute('aria-busy', 'true')
    await userEvent.click(btn)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('wires FormField label, hint, and error to the child control', () => {
    render(
      <FormField label="SKU" hint="Must be unique" error="SKU is required" required>
        <Input placeholder="Enter SKU" />
      </FormField>,
    )
    const input = screen.getByLabelText(/SKU/)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input.getAttribute('aria-describedby')).toBeTruthy()
    expect(screen.getByRole('alert')).toHaveTextContent('SKU is required')
    expect(screen.getByText('Must be unique')).toBeInTheDocument()
  })

  it('sorts DataTable rows and shows loading and empty states', async () => {
    interface Row {
      id: string
      name: string
      qty: number
    }
    const rows: Row[] = [
      { id: '1', name: 'Zebra Tee', qty: 5 },
      { id: '2', name: 'Alpha Cap', qty: 12 },
    ]
    const columns = [
      {
        key: 'name',
        header: 'Product',
        sortable: true,
        sortValue: (r: Row) => r.name,
        render: (r: Row) => r.name,
      },
      {
        key: 'qty',
        header: 'Quantity',
        sortable: true,
        sortValue: (r: Row) => r.qty,
        render: (r: Row) => r.qty,
      },
    ]

    const { rerender } = render(<DataTable columns={columns} rows={rows} getRowKey={(r) => r.id} />)

    // Initial order
    let cells = screen.getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('Zebra Tee')

    // Sort ascending by Product
    await userEvent.click(screen.getByRole('button', { name: /Product/ }))
    cells = screen.getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('Alpha Cap')

    // Sort descending by Product
    await userEvent.click(screen.getByRole('button', { name: /Product/ }))
    cells = screen.getAllByRole('cell')
    expect(cells[0]).toHaveTextContent('Zebra Tee')

    // Empty state
    rerender(
      <DataTable columns={columns} rows={[]} getRowKey={(r) => r.id} emptyTitle="Nothing here" />,
    )
    expect(screen.getByText('Nothing here')).toBeInTheDocument()

    // Loading state
    rerender(<DataTable columns={columns} rows={[]} getRowKey={(r) => r.id} isLoading />)
    expect(screen.getByRole('status', { name: 'Loading table' })).toBeInTheDocument()
  })

  it('opens and closes Modal, ConfirmDialog, and Drawer with Escape', async () => {
    const onClose = vi.fn()
    const onConfirm = vi.fn()
    const { rerender } = render(
      <Modal open title="Edit item" onClose={onClose}>
        <p>Body content</p>
      </Modal>,
    )
    expect(screen.getByRole('dialog', { name: 'Edit item' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)

    rerender(
      <ConfirmDialog
        open
        title="Confirm action"
        confirmLabel="Proceed"
        onClose={onClose}
        onConfirm={onConfirm}
      >
        Are you sure?
      </ConfirmDialog>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Proceed' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)

    rerender(
      <Drawer open title="Movements" onClose={onClose}>
        <p>Drawer body</p>
      </Drawer>,
    )
    expect(screen.getByRole('dialog', { name: 'Movements' })).toBeInTheDocument()
  })

  it('supports keyboard switching in Tabs', async () => {
    render(
      <Tabs
        tabs={[
          { id: 'one', label: 'Profile', content: <p>Profile panel</p> },
          { id: 'two', label: 'Overrides', content: <p>Overrides panel</p> },
        ]}
      />,
    )
    const firstTab = screen.getByRole('tab', { name: 'Profile' })
    firstTab.focus()
    expect(screen.getByText('Profile panel')).toBeInTheDocument()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByText('Overrides panel')).toBeInTheDocument()
  })

  it('formats MoneyText and DateText and omits absent MoneyText values', () => {
    const { container } = render(
      <div>
        <MoneyText amount={12500} />
        <MoneyText amount={undefined} />
        <MoneyText amount={null} />
        <DateText value="2026-11-20T10:00:00Z" />
      </div>,
    )
    expect(screen.getByText('৳ 12,500.00')).toBeInTheDocument()
    expect(screen.getByText('20 Nov 2026')).toBeInTheDocument()
    expect(container.textContent).not.toContain('N/A')
    expect(container.textContent).not.toContain('৳ 0.00')
  })

  it('renders ErrorState with RFC 7807 correlationId and retry callback', async () => {
    const onRetry = vi.fn()
    const error = new ApiError({
      type: 'about:blank',
      title: 'Conflict',
      status: 409,
      detail: 'Barcode is required before activation.',
      instance: '/api/v1/catalog/variants/1/activate',
      errorCode: 'BARCODE_REQUIRED',
      correlationId: 'corr-9876',
      timestamp: '2026-11-20T10:00:00Z',
      fieldErrors: {},
      errors: [],
    })
    render(<ErrorState title="Could not activate" error={error} onRetry={onRetry} />)
    expect(screen.getByText('Barcode is required before activation.')).toBeInTheDocument()
    expect(screen.getByText('corr-9876')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })

  it('renders StatusPill and ToastProvider notifications', async () => {
    function Trigger() {
      const { toast } = useToast()
      return <Button onClick={() => toast({ title: 'Saved item', tone: 'success' })}>Notify</Button>
    }
    render(
      <ToastProvider>
        <StatusPill status="LOW_STOCK" />
        <Trigger />
      </ToastProvider>,
    )
    expect(screen.getByText('Low stock')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Notify' }))
    expect(screen.getByText('Saved item')).toBeInTheDocument()
  })
})
