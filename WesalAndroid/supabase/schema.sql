-- WESAL phone verification backend on Supabase (round 4, Supabase phase).
--
-- Mirrors the Firebase implementation in firebase/functions/src/index.ts:
-- codes live server-only, keyed by phone_hash = sha256 hex of the canonical
-- E.164 number (the same hash space the Android client uses for contacts
-- discovery, PhoneUtils.sha256Hash), and code_hash = sha256(code + phone_hash).
--
-- verified_phones is the Supabase-side replacement for the Firestore
-- phone_uniqueness lock: one phone_hash binds to exactly one firebase_uid,
-- and one firebase_uid binds to exactly one phone_hash.

create table if not exists public.phone_verifications (
  phone_hash        text primary key,
  code_hash         text not null,
  firebase_uid      text not null,
  expires_at        timestamptz not null,
  attempts          int  not null default 0,
  last_sent_at      timestamptz not null,
  send_count        int  not null default 1,
  send_window_start timestamptz not null
);

alter table public.phone_verifications enable row level security;
-- No policies for anon/authenticated on purpose: only the service role
-- (used by the Edge Functions, bypasses RLS) may touch this table.

create table if not exists public.verified_phones (
  phone_hash   text primary key,
  firebase_uid text not null unique,
  phone_e164   text,
  verified_at  timestamptz not null default now()
);

alter table public.verified_phones enable row level security;

-- Contacts discovery: clients may READ the hash -> uid registry with the
-- public anon key. They can never write it; only the service role can.
drop policy if exists verified_phones_select_anon on public.verified_phones;
create policy verified_phones_select_anon
  on public.verified_phones
  for select
  to anon, authenticated
  using (true);

grant select on public.verified_phones to anon, authenticated;

-- «مساعد وِصال» AI assistant (round 8): per-user daily question counter.
-- Written only by the ai-assistant Edge Function (service role).
create table if not exists public.ai_usage (
  firebase_uid text not null,
  day          date not null,
  count        int  not null default 0,
  primary key (firebase_uid, day)
);

alter table public.ai_usage enable row level security;
-- No policies for anon/authenticated on purpose: service role only.
