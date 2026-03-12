package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/metadata"

	pb "factry-historian-proxy/proto/historianpb"
)

type Config struct {
	Host          string `json:"host"`
	Port          int    `json:"port"`
	CollectorUUID string `json:"collectorUUID"`
	Token         string `json:"token"`
}

func loadConfig(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	return &cfg, nil
}

func main() {
	configPath := flag.String("config", "config.json", "Path to config.json")
	flag.Parse()

	cfg, err := loadConfig(*configPath)
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}
	if cfg.CollectorUUID == "" || cfg.Token == "" {
		log.Fatalf("collectorUUID and token must be set in %s", *configPath)
	}

	target := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	log.Printf("Connecting to %s", target)

	tlsCreds := credentials.NewTLS(&tls.Config{InsecureSkipVerify: true})
	conn, err := grpc.NewClient(target, grpc.WithTransportCredentials(tlsCreds))
	if err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewHistorianClient(conn)
	ctx := metadata.AppendToOutgoingContext(context.Background(),
		"collectoruuid", cfg.CollectorUUID,
		"authorization", "Bearer "+cfg.Token,
	)

	resp, err := client.GetMeasurements(ctx, &pb.MeasurementRequest{
		CollectorUUID: cfg.CollectorUUID,
	})
	if err != nil {
		log.Fatalf("GetMeasurements failed: %v", err)
	}

	fmt.Printf("%-40s  %-40s  %-8s  %s\n", "UUID", "NAME", "STATUS", "DATATYPE")
	fmt.Println("----------------------------------------  ----------------------------------------  --------  --------")
	for _, m := range resp.GetMeasurements() {
		fmt.Printf("%-40s  %-40s  %-8s  %s\n",
			m.GetUuid(), m.GetName(), m.GetStatus(), m.GetDatatype())
	}
	fmt.Printf("\nTotal: %d measurements\n", len(resp.GetMeasurements()))
}
