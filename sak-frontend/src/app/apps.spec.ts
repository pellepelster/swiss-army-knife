import { APPS } from './apps';

describe('the apps of the home', () => {
  it('has the backups as an app of every user', () => {
    const app = APPS.find((each) => each.path === '/backups')!;
    expect(app.icon).toBe('icons/backups.svg');
    expect(app.adminOnly).toBeFalsy();
  });
});
