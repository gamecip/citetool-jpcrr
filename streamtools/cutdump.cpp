#include "newpacket.hpp"
#include "timeparse.hpp"
#include <iostream>
#include <sstream>
#include <list>
#include <map>
#include <vector>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <algorithm>

int real_main(int argc, char** argv)
{
	std::map<std::string, uint16_t> channel_assignments;
	std::vector<struct channel> chans;
	uint16_t dummy_channel = 0;
	if(argc != 5) {
		std::cerr << "syntax: muxdump.exe <input> <start> <end> <output>" << std::endl;
		exit(1);
	}

	read_channel rchan(argv[1]);
	write_channel wchan(argv[4]);
	uint64_t low = parse_timespec(argv[2]);
	uint64_t high = parse_timespec(argv[3]);
	if(low > high) {
		std::cerr << "Start of region must be before end." << std::endl;
		exit(1);
	}

	//Create dummy channel.
	channel_assignments["<DUMMY>"] = dummy_channel = (uint16_t)chans.size();
	struct channel c;
	c.c_channel = (uint16_t)chans.size();
	c.c_type = 3;
	c.c_channel_name = "<DUMMY>";
	chans.push_back(c);
	wchan.start_segment(chans);

	packet* p;
	while((p = rchan.read())) {
		if(p->rp_timestamp < low) {
			delete p;
			continue;
		}
		if(p->rp_timestamp > high) {
			delete p;
			break;
		}
		if(!channel_assignments.count(p->rp_channel_name)) {
			//No channel yet, create.
			channel_assignments[p->rp_channel_name] = (uint16_t)chans.size();
			struct channel c;
			c.c_channel = (uint16_t)chans.size();
			c.c_type = p->rp_major;
			c.c_channel_name = p->rp_channel_name;
			chans.push_back(c);
			wchan.start_segment(chans);
		}
		uint16_t chan = channel_assignments[p->rp_channel_name];
		if(chans[chan].c_type != p->rp_major) {
			//Change type.
			chans[chan].c_type = p->rp_major;
			wchan.start_segment(chans);
		}
		p->rp_channel = chan;
		p->rp_timestamp -= low;
		wchan.write(*p);
		delete p;
	}

	struct packet p2;
	p2.rp_channel = dummy_channel;
	p2.rp_timestamp = high - low;
	p2.rp_minor = 0;
	wchan.write(p2);

	return 0;
}
