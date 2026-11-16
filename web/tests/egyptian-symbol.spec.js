import { test, expect } from '@playwright/test';
import contract from './fixtures/egyptian-symbol-contract.json' with { type: 'json' };

test('the generic card renders a Wikipedia-acquired Egyptian deity symbol', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/domains') {
      await route.fulfill({ json: contract.domains });
    } else if (url.pathname === '/api/viewables') {
      await route.fulfill({ json: contract.list });
    } else if (url.pathname === '/api/dimensions') {
      await route.fulfill({ json: [] });
    } else if (url.pathname === '/api/groups') {
      await route.fulfill({ json: null });
    } else if (url.pathname.startsWith('/api/viewable/')) {
      const id = decodeURIComponent(url.pathname.split('/').at(-1));
      await route.fulfill({ json: contract.details[id] });
    } else {
      await route.fulfill({ status: 404, json: { error: 'not part of this contract' } });
    }
  });

  await page.goto('/');
  await page.getByRole('button', { name: 'Amun' }).click();

  await expect(page.getByText('was sceptre')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Amun ↗' }))
    .toHaveAttribute('href', 'https://en.wikipedia.org/wiki/Amun');

  await page.getByRole('button', { name: 'Anubis' }).click();
  await expect(page.locator('.title .name')).toHaveText('Anubis');
  await expect(page.getByText('was sceptre')).toHaveCount(0);
});
