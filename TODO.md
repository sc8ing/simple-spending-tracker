- zio http for scala backend
    - JWT should be set as new cookie in successful endpoints if > some seconds old
    - unavailable pages should redirect to login page
    - GET /api/transaction
    - POST /api/transaction
    - GET / (home)
    - GET /login
    - POST /login
- make docker image for server with frontend
    - Dockerfile sets ENV variables
    - docker volume create --name mysqldb
- deploy.sh script to vultr