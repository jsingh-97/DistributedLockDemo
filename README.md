To run docker containers for redis and postgres
Run `docker-compose up -d`
To see status of all services
Run `docker-compose ps`
Stop (containers preserved, can restart later)
Run `docker-compose stop`
Start previously stopped containers
Run `docker-compose start`
Stops and removes containers(full cleanup)
Run `docker-compose down   `

# Notes

It's always better to put this in production. It runs a startup check that every @Entity class has a matching table with
the right columns and types in the database. If anything's missing or the wrong type, the app fails to start with a
clear error — much better than letting it boot and crash later when a user hits that table.
`ddl-auto: validate` in the application.yml


