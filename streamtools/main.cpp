#ifdef main
#include <SDL_main.h>
#endif
#include <stdexcept>
#include <iostream>

int real_main(int argc, char** argv);

int main(int argc, char** argv)
{
	try {
		return real_main(argc, argv);
	} catch(std::exception& e) {
		std::cerr << "------------------------------------------------------------" << std::endl;
		if(argv[0])
			std::cerr << "Program '" << argv[0] << "' terminated due to error:" << std::endl;
		else
			std::cerr << "Program <unknown> terminated due to error:" << std::endl;
		std::cerr << "Cause: " << e.what() << std::endl;
		std::cerr << "Argument count: " << argc - 1 << std::endl;
		for(int i = 1; i < argc; i++)
			std::cerr << "Argument #" << i << ": '" << argv[i] << "'" << std::endl;
		std::cerr << "------------------------------------------------------------" << std::endl;
		return 128;
	} catch(...) {
		std::cerr << "------------------------------------------------------------" << std::endl;
		if(argv[0])
			std::cerr << "Program '" << argv[0] << "' terminated due to error:" << std::endl;
		else
			std::cerr << "Program <unknown> terminated due to error:" << std::endl;
		std::cerr << "Cause: <Unknown>" << std::endl;
		std::cerr << "Argument count: " << argc - 1 << std::endl;
		for(int i = 1; i < argc; i++)
			std::cerr << "Argument #" << i << ": '" << argv[i] << "'" << std::endl;
		std::cerr << "------------------------------------------------------------" << std::endl;
		return 128;
	}
}
