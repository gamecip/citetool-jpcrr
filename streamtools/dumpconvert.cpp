#include "newpacket.hpp"
#include "misc.hpp"
#include "outputs/public.hpp"
#include "packet-processor.hpp"
#include "temporal-antialias.hpp"
#include <string>
#include <stdexcept>
#include "timeparse.hpp"
#include <iostream>

int real_main(int argc, char** argv)
{
	int dropmode = 0;
	double antialias_factor = 0;
	bool sep = false;
	bool seen_filenames = false;
	struct packet_processor_parameters params;

	//Initialize parameters.
	params.audio_delay = 0;
	params.subtitle_delay = 0;
	params.audio_rate = 44100;
	params.demux = NULL;
	params.width = 0;
	params.height = 0;
	params.rate_num = 0;
	params.rate_denum = 0;
	params.dedup_max = 0;
	params.frame_dropper = NULL;
	params.outgroup = NULL;


	for(int i = 1; i < argc; i++) {
		std::string arg = argv[i];
		if(arg == "--") {
			sep = true;
			if(i + 1 < argc)
				seen_filenames = true;	//There will be filenames.
			break;
		}
		if(!isstringprefix(arg, "--")) {
			seen_filenames = true;		//This is a filename.
			continue;
		}
		try {
			if(isstringprefix(arg, "--video-width=")) {
				std::string value = settingvalue(arg);
				char* x;
				params.width = strtoul(value.c_str(), &x, 10);
				if(*x || !params.width) {
					std::cerr << "--video-width: Bad width" << std::endl;
					return 1;
				}
			} else if(isstringprefix(arg, "--video-height=")) {
				std::string value = settingvalue(arg);
				char* x;
				params.height = strtoul(value.c_str(), &x, 10);
				if(*x || !params.height) {
					std::cerr << "--video-height: Bad height" << std::endl;
					return 1;
				}
			} else if(isstringprefix(arg, "--video-scale-algo=")) {
				//Processed later.
			} else if(arg == "--video-framerate=auto") {
				if(params.rate_denum) {
					std::cerr << "Conflicts with earlier explicit fps: " << arg << "." << std::endl;
					return 1;
				}
				params.rate_num = 1;
			} else if(isstringprefix(arg, "--video-framerate=")) {
				std::string value = settingvalue(arg);
				char* x;
				if(params.rate_num || params.rate_denum) {
					std::cerr << "Conflicts with earlier fps: " << arg << "." << std::endl;
					return 1;
				}
				params.rate_num = strtoul(value.c_str(), &x, 10);
				if((*x != '\0' && *x != '/') || !params.rate_num) {
					std::cerr << "--video-framerate: Bad value (n)" << std::endl;
					return 1;
				}
				if(*x) {
					x++;
					params.rate_denum = strtoul(x, &x, 10);
					if(*x || !params.rate_denum) {
						std::cerr << "--video-framerate: Bad value (d)" << std::endl;
						return 1;
					}
				} else
					params.rate_denum = 1;

			} else if(isstringprefix(arg, "--video-max-dedup=")) {
				std::string value = settingvalue(arg);
				char* x;
				params.dedup_max = strtoul(value.c_str(), &x, 10);
				if(*x) {
					std::cerr << "--video-dedup-max: Bad value" << std::endl;
					return 1;
				}
				if(params.rate_denum) {
					std::cerr << "Conflicts with earlier explicit fps: " << arg << "." << std::endl;
					return 1;
				}
				params.rate_num = 1;
			} else if(isstringprefix(arg, "--audio-rate=")) {
				std::string value = settingvalue(arg);
				char* x;
				params.audio_rate = strtoul(value.c_str(), &x, 10);
			} else if(isstringprefix(arg, "--audio-delay=")) {
				std::string value = settingvalue(arg);
				if(value.length() && value[0] == '-')
					params.audio_delay = -(int64_t)parse_timespec(value.substr(1));
				else
					params.audio_delay = -(int64_t)parse_timespec(value);
			} else if(isstringprefix(arg, "--subtitle-delay=")) {
				std::string value = settingvalue(arg);
				if(value.length() && value[0] == '-')
					params.subtitle_delay = -(int64_t)parse_timespec(value.substr(1));
				else
					params.subtitle_delay = -(int64_t)parse_timespec(value);
			} else if(isstringprefix(arg, "--video-temporalantialias=")) {
				std::string value = settingvalue(arg);
				char* x;
				antialias_factor = strtod(value.c_str(), &x);
				if(*x)
					throw std::runtime_error("Bad blur factor");
				dropmode = 1;
			} else if(isstringprefix(arg, "--audio-mixer-")) {
				//We process these later.
			} else if(isstringprefix(arg, "--video-hardsub-")) {
				//We process these later.
			} else if(isstringprefix(arg, "--output-")) {
				//We process these later.
			} else {
				std::cerr << "Bad option: " << arg << "." << std::endl;
				return 1;
			}
		} catch(std::exception& e) {
			std::cerr << "Error processing option: " << arg << ":" << e.what() << std::endl;
			return 1;
		}

	}

	if(!seen_filenames) {
		std::cout << "usage: " << argv[0] << " [<options>] [--] <filename>..." << std::endl;
		std::cout << "Convert <filename> to variety of raw formats." << std::endl;
		std::cout << "--output-<type>=<file>[,<parameters>]" << std::endl;
		std::cout << "\tSend <type> output to <file>." << std::endl;
		std::cout << "\tSupported types: " << get_output_driver_string() << std::endl;
		std::cout << "--audio-rate=<rate>" << std::endl;
		std::cout << "\tSet audio rate to <rate>. Default 44100." << std::endl;
		std::cout << "--audio-delay=<delay>" << std::endl;
		std::cout << "\tSet audio delay to <delay> (may be negative). Default 0." << std::endl;
		std::cout << "--subtitle-delay=<delay>" << std::endl;
		std::cout << "\tSet subtitle delay to <delay> (may be negative). Default 0." << std::endl;
		std::cout << "--video-width=<width>" << std::endl;
		std::cout << "\tSet video width to <width>." << std::endl;
		std::cout << "--video-height=<height>" << std::endl;
		std::cout << "\tSet video width to <height>." << std::endl;
		std::cout << "--video-scale-algo=<algo>" << std::endl;
		std::cout << "\tSet video scaling algo to <algo>." << std::endl;
		std::cout << "--video-scale-algo=<algo> <w> <h>" << std::endl;
		std::cout << "\tSet video scaling algo for <w>x<h> frames to <algo>." << std::endl;
		std::cout << "\tSupported algorithms: " << get_rescaler_string() << std::endl;
		std::cout << "--video-scale-framerate=<n>[/<d>]" << std::endl;
		std::cout << "\tSet video framerate to <n>/<d>." << std::endl;
		std::cout << "--video-scale-framerate=auto" << std::endl;
		std::cout << "\tSet video framerate to variable." << std::endl;
		std::cout << "--video-max-dedup=<frames>" << std::endl;
		std::cout << "\tSet maximum consequtive frames to elide to <frames>." << std::endl;
		std::cout << "--video-temporalantialias=<factor>" << std::endl;
		std::cout << "\tEnable temporal antialiasing with specified blur factor." << std::endl;
		print_hardsubs_help("--video-hardsub-");
		print_audio_resampler_help("--audio-mixer-");
		return 1;
	}

	subtitle_set_resolution(params.width, params.height);

	if(!params.rate_num && !params.rate_denum) {
		params.rate_num = 60;
		params.rate_denum = 1;
	}

	if(dropmode == 0)
		params.frame_dropper = new framerate_reducer_dropframes();
	else
		params.frame_dropper = new framerate_reducer_temporalantialias(antialias_factor, params.rate_num,
			params.rate_denum);

	audio_settings asettings(params.audio_rate);
	video_settings vsettings(params.width, params.height, params.rate_num, params.rate_denum);
	subtitle_settings ssettings;

	params.outgroup = new output_driver_group();

	params.outgroup->set_audio_settings(asettings);
	params.outgroup->set_video_settings(vsettings);
	params.outgroup->set_subtitle_settings(ssettings);

	sep = false;
	for(int i = 1; i < argc; i++) {
		std::string arg = argv[i];
		if(arg == "--") {
			sep = true;
			break;
		}
		if(!isstringprefix(arg, "--"))
			continue;
		if(isstringprefix(arg, "--output-")) {
			std::string type;
			std::string file;
			std::string parameters;
			size_t x = arg.find_first_of("=");
			if(x > arg.length()) {
				std::cerr << "Bad output specification: " << arg << "." << std::endl;
				return 1;
			}
			type = arg.substr(9, x - 9);
			file = arg.substr(x + 1);
			x = file.find_first_of(",");
			if(x < file.length()) {
				parameters = file.substr(x + 1);
				file = file.substr(0, x);
			}
			params.outgroup->add_driver(type, file, parameters);
		}
	}

	packet_processor& p = create_packet_processor(&params, argc, argv);
	sep = false;
	uint64_t timebase = 0;
	for(int i = 1; i < argc; i++) {
		std::string arg = argv[i];
		if(arg == "--") {
			sep = true;
			continue;
		}
		if(sep || !isstringprefix(arg, "--")) {
			read_channel rc(arg);
			timebase = send_stream(p, rc, timebase);
		}
	}
	p.send_end_of_stream();
	params.outgroup->do_audio_end_callback();
	delete params.outgroup;
	delete &p;
	delete params.frame_dropper;
	return 0;
}
