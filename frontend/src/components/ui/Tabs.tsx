import { useId, useState, type KeyboardEvent, type ReactNode } from 'react'

export interface TabItem {
  id: string
  label: string
  badge?: ReactNode
  content: ReactNode
}

export interface TabsProps {
  tabs: TabItem[]
  activeTab?: string
  defaultTab?: string
  onTabChange?: (tabId: string) => void
  className?: string
}

/**
 * Accessible horizontal tabs with Left/Right keyboard navigation.
 */
export function Tabs({
  tabs,
  activeTab: controlledTab,
  defaultTab,
  onTabChange,
  className = '',
}: TabsProps) {
  const baseId = useId()
  const [internalTab, setInternalTab] = useState(defaultTab ?? tabs[0]?.id ?? '')
  const currentId = controlledTab ?? internalTab

  function selectTab(id: string) {
    if (controlledTab === undefined) {
      setInternalTab(id)
    }
    onTabChange?.(id)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') {
      return
    }
    event.preventDefault()
    const nextIndex =
      event.key === 'ArrowRight'
        ? (index + 1) % tabs.length
        : (index - 1 + tabs.length) % tabs.length
    const target = tabs[nextIndex]
    if (target) {
      selectTab(target.id)
      document.getElementById(`${baseId}-tab-${target.id}`)?.focus()
    }
  }

  const active = tabs.find((tab) => tab.id === currentId) ?? tabs[0]

  return (
    <div className={className}>
      <div role="tablist" className="flex flex-wrap gap-1 border-b border-slate-200">
        {tabs.map((tab, index) => {
          const isSelected = tab.id === active?.id
          return (
            <button
              key={tab.id}
              id={`${baseId}-tab-${tab.id}`}
              type="button"
              role="tab"
              aria-selected={isSelected}
              aria-controls={`${baseId}-panel-${tab.id}`}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => selectTab(tab.id)}
              onKeyDown={(event) => handleKeyDown(event, index)}
              className={[
                '-mb-px inline-flex items-center gap-2 border-b-2 px-4 py-2.5 text-sm font-medium transition-colors',
                'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-merqo-500 rounded-t',
                isSelected
                  ? 'border-merqo-700 text-merqo-800'
                  : 'border-transparent text-slate-600 hover:border-slate-300 hover:text-slate-900',
              ].join(' ')}
            >
              <span>{tab.label}</span>
              {tab.badge}
            </button>
          )
        })}
      </div>

      {active ? (
        <div
          id={`${baseId}-panel-${active.id}`}
          role="tabpanel"
          aria-labelledby={`${baseId}-tab-${active.id}`}
          className="pt-4"
        >
          {active.content}
        </div>
      ) : null}
    </div>
  )
}
