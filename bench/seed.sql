insert into accounts (id, owner_name, balance)
values ('00000000-0000-0000-0000-000000000001', 'Benchmark Seed', 1000000.00)
on conflict (id) do nothing;

insert into transactions (id, account_id, amount, transaction_type)
select
    gen_random_uuid()::text,
    '00000000-0000-0000-0000-000000000001',
    round((random() * 1000)::numeric, 2),
    (array['DEPOSIT','WITHDRAWAL','TRANSFER'])[floor(random() * 3 + 1)]
from generate_series(1, 50000);
