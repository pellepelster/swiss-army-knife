import { expect, test } from '@playwright/test';
import { signIn } from './apps';

// a list with tasks: a done one stays struck through in its place (TODO-3), the purge removes the
// done ones after its undo ran out, an undo brings them back (TODO-6)
test('keeps a todo list, a done task in its place, purges the done ones', async ({
  page,
  request,
}) => {
  const errors: string[] = [];
  page.on('console', (message) => {
    if (message.type() === 'error') errors.push(message.text());
  });
  await signIn(page, request, 'todo@example.org', '/todo');

  const name = `Chores ${Date.now()}`;
  await page.getByRole('link', { name: 'Create list', exact: true }).click();
  await expect(page).toHaveURL('/todo/lists/create');
  await page.getByLabel('Name', { exact: true }).fill(name);
  await page.getByRole('button', { name: 'Create list', exact: true }).click();
  await expect(page).toHaveURL(/\/todo\/lists\/\d+$/);

  const tasks = page.getByRole('list', { name: 'Tasks' });
  for (const title of ['Fix the door', 'Paint the fence']) {
    await page.getByLabel('Task', { exact: true }).fill(title);
    await page.getByRole('button', { name: 'Add task', exact: true }).click();
    await expect(tasks.getByText(title)).toBeVisible();
  }

  // done, it keeps its place
  const door = page.getByRole('button', { name: /^Done: Fix the door/ });
  await door.click();
  await expect(door).toHaveAttribute('aria-pressed', 'true');
  await expect(tasks.getByRole('button', { name: /^Done: / }).first()).toHaveAccessibleName(
    /^Done: Fix the door/,
  );

  // the purge can be undone
  await page.getByRole('button', { name: /Remove done tasks/ }).click();
  await expect(tasks.getByText('Fix the door')).toBeHidden();
  await page.getByRole('button', { name: 'Undo', exact: true }).click();
  await expect(tasks.getByText('Fix the door')).toBeVisible();

  // once the undo ran out it is gone, also after a reload
  await page.getByRole('button', { name: /Remove done tasks/ }).click();
  await expect(page.getByRole('button', { name: 'Undo', exact: true })).toBeHidden({
    timeout: 10_000,
  });
  await page.reload();
  await expect(tasks.getByText('Paint the fence')).toBeVisible();
  await expect(tasks.getByText('Fix the door')).toBeHidden();

  // a task is renamed in its row and deleted for good from its menu (LIST-2)
  await page.getByRole('button', { name: 'Actions of Paint the fence' }).click();
  await page.getByRole('menuitem', { name: 'Rename' }).click();
  await page.getByLabel('Title of Paint the fence').fill('Paint the gate');
  await page.getByLabel('Title of Paint the fence').press('Enter');
  await expect(tasks.getByText('Paint the gate')).toBeVisible();
  await page.reload();
  await expect(tasks.getByText('Paint the gate')).toBeVisible();
  await page.getByRole('button', { name: 'Actions of Paint the gate' }).click();
  await page.getByRole('menuitem', { name: 'Delete' }).click();
  await page.getByLabel('Delete Paint the gate').getByRole('button', { name: 'Delete' }).click();
  await expect(tasks.getByText('Paint the gate')).toBeHidden();
  await page.reload();
  await expect(page.getByText('Paint the gate')).toBeHidden();

  expect(errors).toEqual([]);
});
