import { test, expect } from '@playwright/test';
import contract from './fixtures/linked-text-contract.json' with { type: 'json' };

test('the generic card renders every linked text run at its own destination', async ({ page }) => {
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
  await page.getByRole('button', { name: 'Statement' }).click();

  await expect(page.getByRole('link', { name: 'Q1' }))
    .toHaveAttribute('href', 'https://www.wikidata.org/wiki/Q1');
  await expect(page.getByRole('link', { name: 'P40' }))
    .toHaveAttribute('href', 'https://www.wikidata.org/wiki/Property:P40');
  await expect(page.getByRole('link', { name: 'Child (Q2)' }))
    .toHaveAttribute('href', 'https://www.wikidata.org/wiki/Q2');
  await expect(page.locator('.linked-text').first()).toHaveText('Q1 — P40 → Child (Q2)');
  await page.getByRole('button', { name: 'references (1)' }).click();
  await expect(page.getByRole('link', { name: 'Q3' }))
    .toHaveAttribute('href', 'https://www.wikidata.org/wiki/Q3');
  await expect(page.getByRole('link', { name: 'P31' }))
    .toHaveAttribute('href', 'https://www.wikidata.org/wiki/Property:P31');
  await expect(page.getByRole('link', { name: 'Q5' }))
    .toHaveAttribute('href', 'https://www.wikidata.org/wiki/Q5');
});

test('quiz and pairing treat linked text as an ordinary textual answer field', async ({ page }) => {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/domains') {
      await route.fulfill({ json: contract.domains });
    } else if (url.pathname === '/api/fields') {
      await route.fulfill({ json: [
        { name: 'image', label: 'Image', kind: 'image', expandable: false },
        { name: 'source', label: 'Source', kind: 'linked-text', expandable: false }
      ] });
    } else if (url.pathname === '/api/groups') {
      await route.fulfill({ json: null });
    } else if (url.pathname === '/api/dimensions') {
      await route.fulfill({ json: [] });
    } else {
      await route.fulfill({ status: 404, json: { error: 'not part of this contract' } });
    }
  });

  for (const path of ['/quiz', '/pairing']) {
    await page.goto(path);
    const source = page.locator('.frow').filter({ hasText: 'Source' });
    await expect(source.locator('input').nth(1)).toBeChecked();
  }
});
