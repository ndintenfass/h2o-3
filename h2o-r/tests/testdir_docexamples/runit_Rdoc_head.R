


test.head.golden <- function() {

ausPath <- locate("smalldata/extdata/australia.csv")
australia.hex <- h2o.uploadFile(path = ausPath)
head(australia.hex, 10)
tail(australia.hex, 10)



}

doTest("R Doc head", test.head.golden)
