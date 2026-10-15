package main

import (
	"log"

	appruntime "github.com/Maxoulfou/tcm-backend/golang-version/runtime"
)

func main() {
	if err := appruntime.Start(); err != nil {
		log.Fatal(err)
	}
}
