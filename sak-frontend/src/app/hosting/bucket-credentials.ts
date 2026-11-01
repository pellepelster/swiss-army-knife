import { Component, inject, signal } from '@angular/core';
import { TranslatePipe } from '../i18n/i18n';
import { CopyButton } from '../shared/copy-button';
import { BucketStore } from './bucket-store';
import { s3Endpoint } from './buckets.service';

// the credentials tab of a bucket: the endpoint of the s3 api and the key pair that may access
// this bucket only; the keys are only fetched when asked for, the secret stays hidden until it
// is shown or copied
@Component({
  selector: 'app-bucket-credentials',
  imports: [CopyButton, TranslatePipe],
  template: `
    @if (store.secretError(); as error) {
      <p class="alert alert-danger" role="alert">{{ error }}</p>
    }

    <section class="card card-body" [attr.aria-label]="'hosting.credentials.label' | t">
      <p class="text-body-secondary">
        {{ 'hosting.credentials.intro' | t }}
      </p>
      <dl class="row mb-0">
        <dt class="col-sm-3 fw-normal text-body-secondary">
          {{ 'hosting.credentials.endpoint' | t }}
        </dt>
        <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
          <code class="font-monospace text-break" data-testid="endpoint">{{ endpoint }}</code>
          <app-copy-button
            [text]="endpoint"
            [label]="'hosting.credentials.copy_endpoint' | t"
            (failed)="copyFailed.set($event)"
          />
        </dd>
        <dt class="col-sm-3 fw-normal text-body-secondary">
          {{ 'hosting.credentials.bucket' | t }}
        </dt>
        <dd class="col-sm-9">
          <code class="font-monospace text-break">{{ store.name() }}</code>
        </dd>
        @if (store.secret(); as secret) {
          <dt class="col-sm-3 fw-normal text-body-secondary">
            {{ 'hosting.credentials.access_key' | t }}
          </dt>
          <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
            <code class="font-monospace text-break" data-testid="access-key">{{
              secret.accessKeyId
            }}</code>
            <app-copy-button
              [text]="secret.accessKeyId"
              [label]="'hosting.credentials.copy_access_key' | t"
              (failed)="copyFailed.set($event)"
            />
          </dd>
          <dt class="col-sm-3 fw-normal text-body-secondary">
            {{ 'hosting.credentials.secret_key' | t }}
          </dt>
          <dd class="col-sm-9 d-flex flex-wrap align-items-center gap-2">
            <code class="font-monospace text-break" data-testid="secret-key">{{
              shown() ? secret.secretAccessKey : '••••••••••••••••••••'
            }}</code>
            <button
              type="button"
              class="btn btn-outline-secondary btn-sm"
              [attr.aria-label]="
                (shown()
                  ? 'hosting.credentials.hide_secret_key'
                  : 'hosting.credentials.show_secret_key'
                ) | t
              "
              [title]="
                (shown()
                  ? 'hosting.credentials.hide_secret_key'
                  : 'hosting.credentials.show_secret_key'
                ) | t
              "
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
              [label]="'hosting.credentials.copy_secret_key' | t"
              (failed)="copyFailed.set($event)"
            />
          </dd>
        } @else {
          <dt class="col-sm-3 fw-normal text-body-secondary">
            {{ 'hosting.credentials.keys' | t }}
          </dt>
          <dd class="col-sm-9">
            <button
              type="button"
              class="btn btn-primary btn-sm"
              [disabled]="store.retrievingSecret()"
              (click)="store.retrieveSecret()"
            >
              @if (store.retrievingSecret()) {
                <i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i>
              } @else {
                <i class="fa-solid fa-key" aria-hidden="true"></i>
              }
              {{ 'hosting.credentials.show_keys' | t }}
            </button>
          </dd>
        }
      </dl>
      @if (copyFailed()) {
        <p class="alert alert-danger mb-0" role="alert">
          {{ 'hosting.copy_failed' | t }}
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
