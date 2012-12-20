
create table users(
id integer primary key,
type text,
login text,
name text
);

create table repos(
id integer primary key,
name text,
language text,
description text,
owner_id integer,
foreign key(owner_id) references users(id)
);

create table contribs(
user_id integer,
repo_id integer,
primary key(user_id, repo_id),
foreign key(user_id) references users(id),
foreign key(repo_id) references repos(id)
);

