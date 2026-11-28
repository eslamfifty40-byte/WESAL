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

-- ---------------------------------------------------------------------
-- Failover message plane, Stage 0 (FAILOVER_DESIGN.md §4.2).
-- Tables, indexes, RLS and the atomic write RPCs for the Supabase
-- message plane. Idempotent throughout, so the section is safe to
-- re-run. Prepared 2026-11-27. Whether it has been applied to the
-- live project is tracked outside this file.
-- ---------------------------------------------------------------------
-- Chat membership mirror. The Edge Functions cannot read Firestore
-- (no service-account credential exists for this managed project —
-- §8), so a chat's participant list is registered once, by a
-- participant, the first time the chat routes to the Supabase plane.
-- First-write-wins: the insert is on-conflict-do-nothing, so a mirror
-- can never be overwritten by a later registrant.
create table if not exists public.chat_mirrors (
  chat_id         text primary key,
  kind            text not null check (kind in ('direct','group','support')),
  participant_ids text[] not null,
  created_by      text not null,
  created_at      timestamptz not null default now()
);

-- The message store. message_id is the client's outbox UUID — the same
-- id the Firestore document would have had — so cross-store dedupe is
-- by primary key (§5.3). seq is a global identity used ONLY as a delta
-- cursor (monotonic, gap-tolerant); display ordering uses server_ts.
create table if not exists public.chat_messages (
  message_id                text primary key,
  chat_id                   text not null references public.chat_mirrors(chat_id),
  seq                       bigint generated always as identity,
  sender_id                 text not null,
  sender_name               text not null default '',
  sender_icon_id            text not null default '',
  sender_icon_url           text not null default '',
  sender_verified           boolean not null default false,
  sender_verification_type  text not null default 'BLUE',
  sender_neon_color         text not null default '',
  sender_neon_enabled       boolean not null default false,
  sender_neon_glow_intensity real not null default 0.7,
  type                      text not null default 'TEXT',
  ciphertext                text not null default '',
  body_text                 text not null default '',
  is_encrypted              boolean not null default false,
  is_view_once              boolean not null default false,
  viewed_by                 text[] not null default '{}',
  media_url                 text not null default '',
  media_mime_type           text not null default '',
  media_file_name           text not null default '',
  media_size_bytes          bigint not null default 0,
  media_duration_ms         bigint not null default 0,
  waveform_amplitudes       int[] not null default '{}',
  wrapped_keys              jsonb not null default '{}'::jsonb,
  latitude                  double precision,
  longitude                 double precision,
  location_address          text not null default '',
  live_location_expires_at  bigint not null default 0,
  disappearing_expires_at   bigint not null default 0,
  reply_to_message_id       text,
  reply_to_preview          text,
  forwarded_from_name       text,
  is_edited                 boolean not null default false,
  is_deleted_for_everyone   boolean not null default false,
  deleted_for_users         text[] not null default '{}',
  status                    text not null default 'SENT',
  client_ts                 bigint not null,
  server_ts                 timestamptz not null default now(),
  updated_at                timestamptz not null default now()
);
create index if not exists chat_messages_chat_seq_idx
  on public.chat_messages (chat_id, seq);
create index if not exists chat_messages_chat_server_ts_idx
  on public.chat_messages (chat_id, server_ts);

-- Per-user chat summary, maintained transactionally by the RPCs.
-- This is the Supabase-plane equivalent of the Firestore chats doc's
-- lastMessage*/unreadCounts fields — and the merge source for the
-- chats list (§5.4) and the notification poller (§6).
create table if not exists public.chat_inbox (
  user_id                  text not null,
  chat_id                  text not null references public.chat_mirrors(chat_id),
  last_message_id          text,
  last_message_preview     text not null default '',
  last_message_sender_id   text not null default '',
  last_message_sender_name text not null default '',
  last_message_status      text not null default 'SENT',
  last_message_server_ts   timestamptz,
  last_message_client_ts   bigint not null default 0,
  unread_count             int not null default 0,
  updated_at               timestamptz not null default now(),
  primary key (user_id, chat_id)
);
create index if not exists chat_inbox_user_updated_idx
  on public.chat_inbox (user_id, updated_at desc);

-- Typing plane for Supabase-plane chats (§5.5). Rows are self-expiring
-- by expires_at; readers filter expires_at > now(). No cleanup job is
-- required for correctness.
create table if not exists public.chat_typing (
  chat_id    text not null references public.chat_mirrors(chat_id),
  user_id    text not null,
  expires_at timestamptz not null,
  primary key (chat_id, user_id)
);

-- Self-metering (§9.2): one row per day, incremented by the functions
-- (send / list / inbox / ack counters), readable in the dashboard so
-- consumption against the 500K/month invocation ceiling is visible in
-- one SQL query instead of a surprise.
create table if not exists public.plane_stats (
  day        date not null,
  fn_name    text not null,
  calls      int not null default 0,
  primary key (day, fn_name)
);

-- Optional bootstrap mirror for cold login (§3.4, decision D3).
-- Completeness/gate fields ONLY — never ban fields.
create table if not exists public.profile_mirrors (
  firebase_uid      text primary key,
  profile_completed boolean not null default false,
  phone_hash        text not null default '',
  updated_at        timestamptz not null default now()
);
-- RLS enabled on every message-plane table, with NO policies for
-- anon/authenticated on purpose: only the service role (used by the
-- Edge Functions, bypasses RLS) may touch them — the phone_verifications
-- posture (FAILOVER_DESIGN.md §4.2).
alter table public.chat_mirrors    enable row level security;
alter table public.chat_messages   enable row level security;
alter table public.chat_inbox      enable row level security;
alter table public.chat_typing     enable row level security;
alter table public.plane_stats     enable row level security;
alter table public.profile_mirrors enable row level security;

-- Atomic write RPCs (§4.2). All multi-row writes happen inside these
-- plpgsql functions so a message and its inbox effects commit
-- atomically. The payload of rpc_send_message is the full message field
-- set with snake_case keys matching the chat_messages columns, plus an
-- optional "preview" (derived here from body_text, 80 chars, when the
-- caller does not supply one).

-- Inserts the message (on conflict (message_id) do nothing, reporting
-- whether it landed) and, only when it landed, upserts every
-- participant's chat_inbox row (last-message summary; unread_count + 1
-- for everyone except the sender). The on-conflict guard is what makes
-- a cross-store retry with the same id safe against double unread
-- counts (§5.2).
create or replace function public.rpc_send_message(payload jsonb)
returns jsonb
language plpgsql
as $$
declare
  v_message_id  text := payload->>'message_id';
  v_chat_id     text := payload->>'chat_id';
  v_sender_id   text := payload->>'sender_id';
  v_server_ts   timestamptz;
  v_seq         bigint;
  v_preview     text;
  v_participant text;
  v_viewed_by   text[] := coalesce(
    (select array_agg(e) from jsonb_array_elements_text(
       case when jsonb_typeof(payload->'viewed_by') = 'array'
            then payload->'viewed_by' else '[]'::jsonb end) as e),
    array[]::text[]);
  v_waveform    int[] := coalesce(
    (select array_agg(e::int) from jsonb_array_elements_text(
       case when jsonb_typeof(payload->'waveform_amplitudes') = 'array'
            then payload->'waveform_amplitudes' else '[]'::jsonb end) as e),
    array[]::int[]);
  v_deleted_for text[] := coalesce(
    (select array_agg(e) from jsonb_array_elements_text(
       case when jsonb_typeof(payload->'deleted_for_users') = 'array'
            then payload->'deleted_for_users' else '[]'::jsonb end) as e),
    array[]::text[]);
begin
  insert into public.chat_messages (
    message_id, chat_id, sender_id, sender_name, sender_icon_id,
    sender_icon_url, sender_verified, sender_verification_type,
    sender_neon_color, sender_neon_enabled, sender_neon_glow_intensity,
    type, ciphertext, body_text, is_encrypted, is_view_once, viewed_by,
    media_url, media_mime_type, media_file_name, media_size_bytes,
    media_duration_ms, waveform_amplitudes, wrapped_keys, latitude,
    longitude, location_address, live_location_expires_at,
    disappearing_expires_at, reply_to_message_id, reply_to_preview,
    forwarded_from_name, is_edited, is_deleted_for_everyone,
    deleted_for_users, status, client_ts
  ) values (
    v_message_id,
    v_chat_id,
    v_sender_id,
    coalesce(payload->>'sender_name', ''),
    coalesce(payload->>'sender_icon_id', ''),
    coalesce(payload->>'sender_icon_url', ''),
    coalesce((payload->>'sender_verified')::boolean, false),
    coalesce(payload->>'sender_verification_type', 'BLUE'),
    coalesce(payload->>'sender_neon_color', ''),
    coalesce((payload->>'sender_neon_enabled')::boolean, false),
    coalesce((payload->>'sender_neon_glow_intensity')::real, 0.7),
    coalesce(payload->>'type', 'TEXT'),
    coalesce(payload->>'ciphertext', ''),
    coalesce(payload->>'body_text', ''),
    coalesce((payload->>'is_encrypted')::boolean, false),
    coalesce((payload->>'is_view_once')::boolean, false),
    v_viewed_by,
    coalesce(payload->>'media_url', ''),
    coalesce(payload->>'media_mime_type', ''),
    coalesce(payload->>'media_file_name', ''),
    coalesce((payload->>'media_size_bytes')::bigint, 0),
    coalesce((payload->>'media_duration_ms')::bigint, 0),
    v_waveform,
    coalesce(payload->'wrapped_keys', '{}'::jsonb),
    (payload->>'latitude')::double precision,
    (payload->>'longitude')::double precision,
    coalesce(payload->>'location_address', ''),
    coalesce((payload->>'live_location_expires_at')::bigint, 0),
    coalesce((payload->>'disappearing_expires_at')::bigint, 0),
    payload->>'reply_to_message_id',
    payload->>'reply_to_preview',
    payload->>'forwarded_from_name',
    coalesce((payload->>'is_edited')::boolean, false),
    coalesce((payload->>'is_deleted_for_everyone')::boolean, false),
    v_deleted_for,
    coalesce(payload->>'status', 'SENT'),
    (payload->>'client_ts')::bigint
  )
  on conflict (message_id) do nothing
  returning server_ts, seq into v_server_ts, v_seq;

  if v_server_ts is null then
    -- Cross-store retry of an already-landed message: report the
    -- original landing and change nothing (no double unread counts).
    select server_ts, seq into v_server_ts, v_seq
      from public.chat_messages
     where message_id = v_message_id;
    return jsonb_build_object(
      'landed', false, 'server_ts', v_server_ts, 'seq', v_seq);
  end if;

  v_preview := coalesce(
    payload->>'preview', left(coalesce(payload->>'body_text', ''), 80));

  for v_participant in
    select unnest(participant_ids)
      from public.chat_mirrors
     where chat_id = v_chat_id
  loop
    insert into public.chat_inbox (
      user_id, chat_id, last_message_id, last_message_preview,
      last_message_sender_id, last_message_sender_name,
      last_message_status, last_message_server_ts,
      last_message_client_ts, unread_count, updated_at
    ) values (
      v_participant, v_chat_id, v_message_id, v_preview,
      v_sender_id, coalesce(payload->>'sender_name', ''),
      coalesce(payload->>'status', 'SENT'), v_server_ts,
      (payload->>'client_ts')::bigint,
      case when v_participant = v_sender_id then 0 else 1 end,
      now()
    )
    on conflict (user_id, chat_id) do update set
      last_message_id          = excluded.last_message_id,
      last_message_preview     = excluded.last_message_preview,
      last_message_sender_id   = excluded.last_message_sender_id,
      last_message_sender_name = excluded.last_message_sender_name,
      last_message_status      = excluded.last_message_status,
      last_message_server_ts   = excluded.last_message_server_ts,
      last_message_client_ts   = excluded.last_message_client_ts,
      unread_count             = public.chat_inbox.unread_count
        + case when public.chat_inbox.user_id = v_sender_id then 0 else 1 end,
      updated_at               = now();
  end loop;

  return jsonb_build_object(
    'landed', true, 'server_ts', v_server_ts, 'seq', v_seq);
end;
$$;

-- Sets status on the messages the reader is acking (monotonic:
-- SENT -> DELIVERED -> READ, never backwards) and, for READ, zeroes the
-- reader's inbox unread_count for the chat in the same transaction.
create or replace function public.rpc_ack_messages(
  p_chat_id text,
  p_reader  text,
  p_ids     text[],
  p_status  text
)
returns void
language plpgsql
as $$
declare
  v_new_rank int := case p_status
                      when 'READ' then 2
                      when 'DELIVERED' then 1
                      else 0
                    end;
begin
  if v_new_rank = 0 then
    return;  -- Only DELIVERED / READ ever advance a message.
  end if;

  update public.chat_messages m
     set status = p_status,
         updated_at = now()
   where m.chat_id = p_chat_id
     and m.message_id = any (p_ids)
     and m.sender_id <> p_reader
     and (case m.status
            when 'READ' then 2
            when 'DELIVERED' then 1
            else 0
          end) < v_new_rank;

  if p_status = 'READ' then
    update public.chat_inbox
       set unread_count = 0,
           updated_at = now()
     where user_id = p_reader
       and chat_id = p_chat_id;
  end if;
end;
$$;

-- Upserts or deletes the caller's typing row with a 6-second expiry
-- (the client's existing 2.5 s idle timer + refresh cadence rides on
-- top, §5.5).
create or replace function public.rpc_set_typing(
  p_chat_id text,
  p_user    text,
  p_on      boolean
)
returns void
language plpgsql
as $$
begin
  if p_on then
    insert into public.chat_typing (chat_id, user_id, expires_at)
    values (p_chat_id, p_user, now() + interval '6 seconds')
    on conflict (chat_id, user_id)
      do update set expires_at = excluded.expires_at;
  else
    delete from public.chat_typing
     where chat_id = p_chat_id
       and user_id = p_user;
  end if;
end;
$$;

-- The RPCs are service-role only, like the tables: the Edge Functions
-- call them through the service role after verifying the caller's
-- Firebase ID token and chat membership themselves.
revoke all on function public.rpc_send_message(jsonb)
  from public, anon, authenticated;
revoke all on function public.rpc_ack_messages(text, text, text[], text)
  from public, anon, authenticated;
revoke all on function public.rpc_set_typing(text, text, boolean)
  from public, anon, authenticated;
grant execute on function public.rpc_send_message(jsonb) to service_role;
grant execute on function public.rpc_ack_messages(text, text, text[], text)
  to service_role;
grant execute on function public.rpc_set_typing(text, text, boolean)
  to service_role;
