library(h2o)
h2o.init()

filePath <- normalizePath(h2o:::.h2o.locate("smalldata/airlines/allyears2k_headers.zip"))

df <- h2o.importFile(filePath, "df")

s <- h2o.runif(df)
air.train <- df[s <= 0.8,]
h2o.assign(air.train, ("air.train"))
air.valid <- df[s > 0.8,]
h2o.assign(air.valid, ("air.valid"))

# Data set column headers
# Year,Month,DayofMonth,DayOfWeek,DepTime,CRSDepTime,ArrTime,CRSArrTime,UniqueCarrier,FlightNum,TailNum,ActualElapsedTime,CRSElapsedTime,AirTime,ArrDelay,DepDelay,Origin,Dest,Distance,TaxiIn,TaxiOut,Cancelled,CancellationCode,Diverted,CarrierDelay,WeatherDelay,NASDelay,SecurityDelay,LateAircraftDelay,IsArrDelayed,IsDepDelayed

myX <- c("Year", "Month", "DayofMonth", "DayOfWeek", "CRSDepTime", "CRSArrTime",
      "UniqueCarrier", "FlightNum", "CRSElapsedTime", "Origin", "Dest", "Distance")
myY <- "IsDepDelayed"

air.gbm <- h2o.gbm(training_frame = air.train, validation_frame = air.valid,
                x = myX, y = myY, distribution = "multinomial",
                ntrees = c(5, 10), max_depth = c(3, 5))

air.drf <- h2o.randomForest(training_frame = air.train, validation_frame = air.valid,
                         x = myX, y = myY,
                         ntrees = c(5, 10), depth = c(5, 10))

# air.srf <- h2o.randomForest(data = air.train, validation = air.valid,
#                            x = myX, y = myY,
#                            ntree = c(5, 10), depth = c(5, 10),
#                            importance = TRUE,
#                            type = "fast")

air.glm <- h2o.glm(training_frame = air.train,
                x = myX, y = myY,
                family = "binomial",
                alpha = c(0.1, 0.2, 0.5),
                standardize =)

air.dl <- h2o.deeplearning(training_frame = air.train, validation_frame = air.valid,
                        x = myX, y = myY,
                        classification = TRUE,
                        activation = c("Tanh", "Rectifier"),
                        hidden = list(c(5, 5), c(10,10)),
                        use_all_factor_levels = TRUE, variable_importances = TRUE)

message <- sprintf("%sPoint your web browser to:  http://%s:%s/steam/index.html\n%s",
                "----------\n\n",
                conn@ip, conn@port,
                "\n----------\n"
                )
cat(message)