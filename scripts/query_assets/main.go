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
	"google.golang.org/grpc/credentials/insecure"
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
	plaintext := flag.Bool("plaintext", false, "Use plaintext gRPC (no TLS) for fake server")
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

	var dialOpt grpc.DialOption
	if *plaintext {
		dialOpt = grpc.WithTransportCredentials(insecure.NewCredentials())
	} else {
		dialOpt = grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{InsecureSkipVerify: true}))
	}
	conn, err := grpc.NewClient(target, dialOpt)
	if err != nil {
		log.Fatalf("Failed to connect: %v", err)
	}
	defer conn.Close()

	client := pb.NewHistorianClient(conn)
	ctx := metadata.AppendToOutgoingContext(context.Background(),
		"collectoruuid", cfg.CollectorUUID,
		"authorization", "Bearer "+cfg.Token,
	)

	resp, err := client.GetAssets(ctx, &pb.AssetRequest{})
	if err != nil {
		log.Fatalf("GetAssets failed: %v", err)
	}

	fmt.Printf("%-40s  %-30s  %-8s  %s\n", "UUID", "NAME", "STATUS", "DATATYPE")
	fmt.Println("----------------------------------------  ------------------------------  --------  --------")
	for _, a := range resp.GetAssets() {
		fmt.Printf("%-40s  %-30s  %-8s  %s\n",
			a.GetUuid(), a.GetName(), a.GetStatus(), a.GetDatatype())
	}
	fmt.Printf("\nTotal: %d assets\n", len(resp.GetAssets()))
}
