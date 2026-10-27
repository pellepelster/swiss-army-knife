import { Component, inject, signal } from '@angular/core';
import { CopyButton } from '../shared/copy-button';
import { BucketStore } from './bucket-store';
import { s3Endpoint } from './buckets.service';

// the credentials tab of a bucket: the endpoint of the s3 api and the key pair that may access
// this bucket only; the keys are only fetched when asked for, the secret stays hidden until it
// is shown or copied
@Component({
  selector: 'app-bucket-credentials',
  imports: [CopyButton],
  styles: `
    .value {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .icon-button {
      display: inline-grid;
      place-items: center;
      width: 32px;
      height: 32px;
      border: 1px solid var(--line);
      border-radius: 8px;
      background: transparent;
      color: var(--ink);
      cursor: pointer;
    }
    .intro {
      margin: 0 0 14px;
    }
  `,
  template: `
    @if (store.secretError(); as error) {
      <p class="error" role="alert">{{ error }}</p>
    }

    <section class="sheet" aria-label="Credentials">
      <p class="intro muted">
        Upload the files of the website with any S3 client, e.g. the AWS CLI. The key pair may
        access this bucket only.
      </p>
      <dl class="facts">
        <dt>Endpoint</dt>
        <dd class="value">
          <code class="mono" data-testid="endpoint">{{ endpoint }}</code>
          <app-copy-button
            [text]="endpoint"
            label="Copy endpoint"
            (failed)="copyFailed.set($event)"
          />
        </dd>
        <dt>Bucket</dt>
        <dd>
          <code class="mono">{{ store.name() }}</code>
        </dd>
        @if (store.secret(); as secret) {
          <dt>Access key</dt>
          <dd class="value">
            <code class="mono" data-testid="access-key">{{ secret.accessKeyId }}</code>
            <app-copy-button
              [text]="secret.accessKeyId"
              label="Copy access key"
              (failed)="copyFailed.set($event)"
            />
          </dd>
          <dt>Secret key</dt>
          <dd class="value">
            <code class="mono" data-testid="secret-key">{{
              shown() ? secret.secretAccessKey : '••••••••••••••••••••'
            }}</code>
            <button
              type="button"
              class="icon-button"
              [attr.aria-label]="shown() ? 'Hide secret key' : 'Show secret key'"
              [title]="shown() ? 'Hide secret key' : 'Show secret key'"
              [attr.aria-pressed]="shown()"
              (click)="shown.set(!shown())"
            >
              <i
                class="fa-solid"
                [class.fa-eye-slash]="shown()"
                [class.fa-eye]="!shown()"
                aria-hidden="true"
              ></i>
            </button>
            <app-copy-button
              [text]="secret.secretAccessKey"
              label="Copy secret key"
              (failed)="copyFailed.set($event)"
            />
          </dd>
        } @else {
          <dt>Keys</dt>
          <dd>
            <button
              type="button"
              class="button button-small"
              [disabled]="store.retrievingSecret()"
              (click)="store.retrieveSecret()"
            >
              @if (store.retrievingSecret()) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              } @else {
                <i class="fa-solid fa-key" aria-hidden="true"></i>
              }
              Show the key pair
            </button>
          </dd>
        }
      </dl>
      @if (copyFailed()) {
        <p class="error" role="alert">
          The browser did not allow to copy, please select the text instead.
        </p>
      }
    </section>
  `,
})
export class BucketCredentials {
  protected readonly store = inject(BucketStore);

  protected readonly endpoint = s3Endpoint();
  protected readonly shown = signal(false);
  protected readonly copyFailed = signal(false);
}
