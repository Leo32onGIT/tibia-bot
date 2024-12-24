# Violent Bot

Production:
- [Website](https://violentbot.xyz)
- [Discord App Directory](https://discord.com/application-directory/1067774715407642624)
   
Current features include:    
- Online List
- Levels List
- Deaths List
- Activity List (guild & name changes)
- Poke if enemy dies fullbless
- Poke if anyone dies to a rare nemesis boss

## Pre-requisites:

#### Create the new bot in Discord
1. Go to: https://discord.com/developers/applications and create a **New Application**.
2. Go to the **Bot** tab and click on **Add Bot**.
3. Click **Reset Token** & take note of the `Token` that is generated.

#### Custom Emojis and Poke Roles
The bot is configured to point to emojis and roles in _my_ discord server.     
You will need to change this to point to your emojis and your discord roles.

1. Open the [discord.conf](https://github.com/Leo32onGIT/tibia-bot/blob/dedicated/tibia-bot/src/main/resources/discord.conf#L17-L55) file and edit it.
2. Point to `emoji ids` to ones that exist on _your_ discord server.

#### Prepare your linux machine to host the bot
1. Ensure `docker` is installed.
1. Ensure `default-jre` is installed.
1. Ensure `sbt` is installed.
3. Download the `postgres` docker image:    
`docker pull postgres`

## Deployment Steps

1. Clone the repository to your host machine.    
2. Compile the code into a docker image:    
`sbt docker:publishLocal`    
3. Take note of the docker \<image id\> you just created: `docker images`   
> ![docker image id](https://i.imgur.com/nXvSeIL.png)

4. Create a `prod.env` file with the discord server/channel id & bot authentication token:
> ```env
> TOKEN=XXXXXXXXXXXXXXXXXXXXXX   
> POSTGRES_HOST=sqlhost
> POSTGRES_PASSWORD=XXXXXXXXXX
> ```
5. Create the docker volume for the postgres database:    
`docker volume create --name pgdata`
6. Run the postgres docker image:    
`docker run --rm -d -t --env-file prod.env --hostname sqlhost --name postgres -p 5432:5432 -v pgdata:/var/lib/postgresql/data postgres`
7. Run the docker container you just created & parse the **prod.env** file:     
`docker run --rm -d -t --env-file prod.env --link postgres:postgres --name tibia-bot <image_id>`

## Debugging

1. If something isn't working correctly you should be able to see why very clearly in the logs.
2. Use `docker ps` to find the \<container id\> for the running bot.
3. Use `docker logs <container id>` to view the logs.
4. Use `docker pull dpage/pgadmin4` and `docker run -t --name pgadmin -p 0.0.0.0:82:80 --link postgres:postgres -e 'PGADMIN_DEFAULT_EMAIL=XXXXXXX@gmail.com' -e 'PGADMIN_DEFAULT_PASSWORD=XXXXXXXX' -d dpage/pgadmin4` if you need to visualise the postgres dbs
