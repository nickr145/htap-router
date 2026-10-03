drop table if exists transactions;
drop table if exists accounts;

create table if not exists accounts (
    id varchar(36) primary key,
    owner_name varchar(255) not null,
    balance numeric(15,2) not null default 0.00,
    created_at timestamp with time zone default current_timestamp
);

create table if not exists transactions (
    id varchar(36) primary key,
    seq bigint generated always as identity,
    account_id varchar(36) not null references accounts(id),
    amount numeric(15,2) not null,
    transaction_type varchar(20) not null check (transaction_type in ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
    created_at timestamp with time zone default current_timestamp
);

create index if not exists idx_transactions_account_id on transactions(account_id);
create index if not exists idx_transactions_account_id_created_at on transactions(account_id, created_at desc);

