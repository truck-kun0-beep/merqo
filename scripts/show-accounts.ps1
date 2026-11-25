# Shows the accounts in the test database.
param(
    [string] $Database = 'merqo_test'
)

$env:PGPASSWORD = 'postgres'
& 'G:\tools\pgtx2\bin\psql.exe' -h localhost -U postgres -d $Database -c `
    "select username, account_type, is_demo, created_at from app_users order by created_at;"
