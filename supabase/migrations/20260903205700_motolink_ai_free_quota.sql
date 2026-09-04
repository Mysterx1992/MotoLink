-- MotoLink AI free-only quota counter.
-- Stores only day + aggregate call count. No questions, chat, diagnostics or user identifiers.

create table if not exists public.motolink_ai_usage (
  usage_day date primary key,
  groq_calls integer not null default 0 check (groq_calls >= 0),
  updated_at timestamptz not null default now()
);

alter table public.motolink_ai_usage enable row level security;
revoke all on table public.motolink_ai_usage from public, anon, authenticated;
grant all on table public.motolink_ai_usage to service_role;

create or replace function public.consume_motolink_ai_quota(p_limit integer)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
  current_count integer;
begin
  if p_limit is null or p_limit < 1 then
    return false;
  end if;

  insert into public.motolink_ai_usage (usage_day, groq_calls, updated_at)
  values (current_date, 0, now())
  on conflict (usage_day) do nothing;

  update public.motolink_ai_usage
  set groq_calls = groq_calls + 1,
      updated_at = now()
  where usage_day = current_date
    and groq_calls < p_limit
  returning groq_calls into current_count;

  return current_count is not null;
end;
$$;

revoke all on function public.consume_motolink_ai_quota(integer) from public, anon, authenticated;
grant execute on function public.consume_motolink_ai_quota(integer) to service_role;
