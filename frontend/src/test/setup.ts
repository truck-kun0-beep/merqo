import '@testing-library/jest-dom/vitest'
import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

// Every test starts with an empty DOM so one test cannot influence another.
afterEach(() => {
  cleanup()
})
