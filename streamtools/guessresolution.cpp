#include <map>
#include <iostream>
#include <stdint.h>
#include "newpacket.hpp"

int real_main(int argc, char** argv)
{
	uint64_t na_time = 0;
	std::map<std::pair<uint32_t, uint32_t>, uint64_t> res_time;
	uint32_t current_width = 0, current_height = 0;
	uint64_t last_timestamp = 0;
	uint64_t last_timestamp2 = 0;
	uint64_t time_correction = 0;

	if(argc == 1) {
		std::cerr << "Syntax: guessresolution.exe <files>..." << std::endl;
		return 1;
	}
	for(int i = 1; i < argc; i++) {
		read_channel in(argv[i]);
		time_correction = last_timestamp;
		packet* p;
		while((p = in.read())) {
			p->rp_timestamp += time_correction;
			last_timestamp = p->rp_timestamp;
			if(current_width != 0 && current_height != 0) {
				std::pair<uint32_t, uint32_t> res = std::make_pair(current_width, current_height);
				if(!res_time.count(res))
					res_time[res] = 0;
				res_time[res] = res_time[res] + (p->rp_timestamp - last_timestamp2);
				last_timestamp2 = p->rp_timestamp;
			} else {
				na_time = na_time + (p->rp_timestamp - last_timestamp2);
				last_timestamp2 = p->rp_timestamp;
			}
			if(p->rp_major == 0 && p->rp_payload.size() >= 4) {
				current_width = ((uint32_t)p->rp_payload[0] << 8) | p->rp_payload[1];
				current_height = ((uint32_t)p->rp_payload[2] << 8) | p->rp_payload[3];
			}
			delete p;
		}
	}

	uint32_t gwidth = 0, gheight = 0;
	uint64_t gtime = 0;
	std::cout << "<no video data> for " << (na_time + 500000) / 1000000 << "ms." << std::endl;
	for(std::map<std::pair<uint32_t, uint32_t>, uint64_t>::iterator j = res_time.begin(); j != res_time.end(); ++j) {
		std::cout << j->first.first << "*" << j->first.second << " for " << (j->second + 500000) / 1000000 << "ms."
			<< std::endl;
		if(j->second > gtime) {
			gwidth = j->first.first;
			gheight = j->first.second;
			gtime = j->second;
		}
	}
	if(gwidth)
		std::cout << "Guessed resolution is " << gwidth << "*" << gheight << "." << std::endl;
	else
		std::cout << "Warning: No video data available." << std::endl;
	return 0;
}
